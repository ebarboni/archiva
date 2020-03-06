package org.apache.archiva.repository.content.maven2;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.common.filelock.FileLockManager;
import org.apache.archiva.common.utils.FileUtils;
import org.apache.archiva.common.utils.VersionUtil;
import org.apache.archiva.configuration.FileTypes;
import org.apache.archiva.maven2.metadata.MavenMetadataReader;
import org.apache.archiva.metadata.repository.storage.RepositoryPathTranslator;
import org.apache.archiva.metadata.repository.storage.maven2.ArtifactMappingProvider;
import org.apache.archiva.metadata.repository.storage.maven2.DefaultArtifactMappingProvider;
import org.apache.archiva.model.ArchivaArtifact;
import org.apache.archiva.model.ArtifactReference;
import org.apache.archiva.model.ProjectReference;
import org.apache.archiva.model.VersionedReference;
import org.apache.archiva.repository.ContentAccessException;
import org.apache.archiva.repository.ContentNotFoundException;
import org.apache.archiva.repository.EditableManagedRepository;
import org.apache.archiva.repository.LayoutException;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.content.Artifact;
import org.apache.archiva.repository.content.ContentItem;
import org.apache.archiva.repository.content.ItemNotFoundException;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.Namespace;
import org.apache.archiva.repository.content.Project;
import org.apache.archiva.repository.content.Version;
import org.apache.archiva.repository.content.base.ArchivaNamespace;
import org.apache.archiva.repository.content.base.ArchivaProject;
import org.apache.archiva.repository.content.base.ArchivaVersion;
import org.apache.archiva.repository.content.base.builder.ArtifactOptBuilder;
import org.apache.archiva.repository.storage.RepositoryStorage;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.archiva.repository.storage.util.StorageUtil;
import org.apache.commons.collections4.map.ReferenceMap;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ManagedDefaultRepositoryContent
 */
public class ManagedDefaultRepositoryContent
    extends AbstractDefaultRepositoryContent
    implements ManagedRepositoryContent
{

    public  static final String METADATA_FILENAME = "maven-metadata.xml";
    private FileTypes filetypes;

    public void setFileTypes(FileTypes fileTypes) {
        this.filetypes = fileTypes;
    }

    private ManagedRepository repository;

    private FileLockManager lockManager;

    @Inject
    @Named("repositoryPathTranslator#maven2")
    private RepositoryPathTranslator pathTranslator;

    @Inject
    @Named( "metadataReader#maven" )
    MavenMetadataReader metadataReader;

    @Inject
    @Named( "MavenContentHelper" )
    MavenContentHelper mavenContentHelper;

    public static final String SNAPSHOT = "SNAPSHOT";

    public static final Pattern UNIQUE_SNAPSHOT_PATTERN = Pattern.compile( "^(SNAPSHOT|[0-9]{8}\\.[0-9]{6}-[0-9]+)(.*)" );
    public static final Pattern CLASSIFIER_PATTERN = Pattern.compile( "^-([^.]+)(\\..*)" );

    public static final Pattern TIMESTAMP_PATTERN = Pattern.compile( "^([0-9]{8})\\.([0-9]{6})$" );

    public static final Pattern GENERIC_SNAPSHOT_PATTERN = Pattern.compile( "^(.*)-" + SNAPSHOT );

    /**
     * We are caching content items in a weak reference map. To avoid always recreating the
     * the hierarchical structure.
     * TODO: Better use a object cache? E.g. our spring cache implementation?
     */
    private ReferenceMap<String, Namespace> namespaceMap = new ReferenceMap<>( );
    private ReferenceMap<StorageAsset, Project> projectMap = new ReferenceMap<>( );
    private ReferenceMap<StorageAsset, Version> versionMap = new ReferenceMap<>( );
    private ReferenceMap<StorageAsset, Artifact> artifactMap = new ReferenceMap<>( );

    public ManagedDefaultRepositoryContent() {
        super(Collections.singletonList( new DefaultArtifactMappingProvider() ));
    }

    public ManagedDefaultRepositoryContent(ManagedRepository repository, FileTypes fileTypes, FileLockManager lockManager) {
        super(Collections.singletonList( new DefaultArtifactMappingProvider() ));
        setFileTypes( fileTypes );
        this.lockManager = lockManager;
        setRepository( repository );
    }

    public ManagedDefaultRepositoryContent( ManagedRepository repository, List<? extends ArtifactMappingProvider> artifactMappingProviders, FileTypes fileTypes, FileLockManager lockManager )
    {
        super(artifactMappingProviders==null ? Collections.singletonList( new DefaultArtifactMappingProvider() ) : artifactMappingProviders);
        setFileTypes( fileTypes );
        this.lockManager = lockManager;
        setRepository( repository );

    }

    /**
     * Returns a version reference from the coordinates
     * @param groupId the group id
     * @param artifactId the artifact id
     * @param version the version
     * @return the versioned reference object
     */
    @Override
    public VersionedReference toVersion( String groupId, String artifactId, String version ) {
        return new VersionedReference().groupId( groupId ).artifactId( artifactId ).version( version );
    }

    @Override
    public VersionedReference toGenericVersion( ArtifactReference artifactReference )
    {
        return toVersion( artifactReference.getGroupId( ), artifactReference.getArtifactId( ), VersionUtil.getBaseVersion( artifactReference.getVersion( ) ));
    }

    /**
     * Return the version the artifact is part of
     * @param artifactReference
     * @return
     */
    public VersionedReference toVersion( ArtifactReference artifactReference) {
        return toVersion( artifactReference.getGroupId( ), artifactReference.getArtifactId( ), artifactReference.getVersion( ) );
    }

    @Override
    public ArtifactReference toArtifact( String groupId, String artifactId, String version, String type, String classifier) {
        return new ArtifactReference( ).groupId( groupId ).artifactId( artifactId ).version( version ).type( type ).classifier( classifier );
    }

    @Override
    public void deleteItem( ContentItem item ) throws ItemNotFoundException, ContentAccessException
    {
        final Path baseDirectory = getRepoDir( );
        final Path itemPath = item.getAsset( ).getFilePath( );
        if ( !Files.exists( itemPath ) )
        {
            throw new ItemNotFoundException( "The item " + item.toString() + "does not exist in the repository " + getId( ) );
        }
        if ( !itemPath.toAbsolutePath().startsWith( baseDirectory.toAbsolutePath() ) )
        {
            log.error( "The namespace {} to delete from repository {} is not a subdirectory of the repository base.", item, getId( ) );
            log.error( "Namespace directory: {}", itemPath );
            log.error( "Repository directory: {}", baseDirectory );
            throw new ContentAccessException( "Inconsistent directories found. Could not delete namespace." );
        }
        try
        {
            if (Files.isDirectory( itemPath ))
            {
                FileUtils.deleteDirectory( itemPath );
            } else {
                Files.deleteIfExists( itemPath );
            }
        }
        catch ( IOException e )
        {
            log.error( "Could not delete namespace directory {}: {}", itemPath, e.getMessage( ), e );
            throw new ContentAccessException( "Error occured while deleting namespace " + item + ": " + e.getMessage( ), e );
        }
    }

    private StorageAsset getAssetByPath(String assetPath) {
        return getStorage( ).getAsset( assetPath );
    }

    private StorageAsset getAsset(String namespace) {
        String namespacePath = formatAsDirectory( namespace.trim() );
        if (StringUtils.isEmpty( namespacePath )) {
            namespacePath = "";
        }
        return getAssetByPath(namespacePath);
    }

    private StorageAsset getAsset(String namespace, String project) {
        return getAsset( namespace ).resolve( project );
    }

    private StorageAsset getAsset(String namespace, String project, String version) {
        return getAsset( namespace, project ).resolve( version );
    }

    private StorageAsset getAsset(String namespace, String project, String version, String fileName) {
        return getAsset( namespace, project, version ).resolve( fileName );
    }


    @Override
    public Namespace getNamespace( final ItemSelector namespaceSelector ) throws ContentAccessException, IllegalArgumentException
    {
        return namespaceMap.computeIfAbsent( namespaceSelector.getNamespace(),
            namespace -> {
                StorageAsset nsPath = getAsset( namespace );
                return ArchivaNamespace.withRepository( this ).withAsset( nsPath ).
                    withNamespace( namespace ).build( );
            });
    }


    @Override
    public Project getProject( final ItemSelector selector ) throws ContentAccessException, IllegalArgumentException
    {
        if (!selector.hasProjectId()) {
            throw new IllegalArgumentException( "Project id must be set" );
        }
        final StorageAsset path = getAsset( selector.getNamespace( ), selector.getProjectId( ) );
        return projectMap.computeIfAbsent( path, projectPath -> {
            final Namespace ns = getNamespace( selector );
            return ArchivaProject.withAsset( projectPath ).withNamespace( ns ).withId( selector.getProjectId( ) ).build( );
        }
        );
    }


    @Override
    public Version getVersion( final ItemSelector selector ) throws ContentAccessException, IllegalArgumentException
    {
        if (!selector.hasProjectId()) {
            throw new IllegalArgumentException( "Project id must be set" );
        }
        if (!selector.hasVersion() ) {
            throw new IllegalArgumentException( "Version must be set" );
        }
        final StorageAsset path = getAsset(selector.getNamespace(), selector.getProjectId(), selector.getVersion());
        return versionMap.computeIfAbsent( path, versionPath -> {
            final Project project = getProject( selector );
            return ArchivaVersion.withAsset( path )
                .withProject( project )
                .withVersion( selector.getVersion( ) ).build();
        } );
    }



    public Artifact createArtifact(final StorageAsset artifactPath, final ItemSelector selector,
        final String classifier, final String extension) {
        Version version = getVersion(selector);
        ArtifactOptBuilder builder = org.apache.archiva.repository.content.base.ArchivaArtifact.withAsset( artifactPath )
            .withVersion( version )
            .withId( selector.getArtifactId( ) )
            .withArtifactVersion( mavenContentHelper.getArtifactVersion( artifactPath, selector ) )
            .withClassifier( classifier );
        if (selector.hasType()) {
            builder.withType( selector.getType( ) );
        }
        return builder.build( );
    }

    public Namespace getNamespaceFromArtifactPath( final StorageAsset artifactPath) {
        final StorageAsset namespacePath = artifactPath.getParent( ).getParent( ).getParent( );
        final String namespace = MavenContentHelper.getNamespaceFromNamespacePath( namespacePath );
        return namespaceMap.computeIfAbsent( namespace,
            myNamespace -> ArchivaNamespace.withRepository( this )
                .withAsset( namespacePath )
                .withNamespace( namespace )
                .build( ) );
    }

    private Project getProjectFromArtifactPath( final StorageAsset artifactPath) {
        final StorageAsset projectPath = artifactPath.getParent( ).getParent( );
        return projectMap.computeIfAbsent( projectPath,
            myProjectPath -> ArchivaProject.withAsset( projectPath )
                .withNamespace( getNamespaceFromArtifactPath( artifactPath ) )
                .withId( projectPath.getName( ) ).build( )
        );
    }

    private Version getVersionFromArtifactPath( final StorageAsset artifactPath) {
        final StorageAsset versionPath = artifactPath.getParent( );
        return versionMap.computeIfAbsent( versionPath,
            myVersionPath -> ArchivaVersion.withAsset( versionPath )
                .withProject( getProjectFromArtifactPath( artifactPath ) )
                .withVersion( versionPath.getName( ) ).build( ) );
    }

    private Artifact getArtifactFromPath(final StorageAsset artifactPath) {
        final Version version = getVersionFromArtifactPath( artifactPath );
        final ArtifactInfo info  = getArtifactInfoFromPath( version.getVersion(), artifactPath );
        return artifactMap.computeIfAbsent( artifactPath, myArtifactPath ->
            org.apache.archiva.repository.content.base.ArchivaArtifact.withAsset( artifactPath )
                .withVersion( version )
                .withId( info.id )
                .withClassifier( info.classifier )
                .withRemainder( info.remainder )
                .withType( info.type )
                .withArtifactVersion( info.version )
                .withContentType( info.contentType )
                .build( )
        );
    }

    private ContentItem getItemFromPath(final StorageAsset itemPath) {
        if (itemPath.isLeaf()) {
            return getArtifactFromPath( itemPath );
        } else {
            if (versionMap.containsKey( itemPath )) {
                return versionMap.get( itemPath );
            }
            if (projectMap.containsKey( itemPath )) {
                return projectMap.get( itemPath );
            }
            String ns = MavenContentHelper.getNamespaceFromNamespacePath( itemPath );
            if (namespaceMap.containsKey( ns )) {
                return namespaceMap.get( ns );
            }
            // No cached item, so we have to gather more information:
            // Check for version directory (contains at least a pom or metadata file)
            if (itemPath.list( ).stream( ).map(a -> a.getName().toLowerCase()).anyMatch( n ->
                n.endsWith( ".pom" )
                || n.startsWith( "maven-metadata" )
            )) {
                return versionMap.computeIfAbsent( itemPath,
                    myVersionPath -> ArchivaVersion.withAsset( itemPath )
                        .withProject( (Project)getItemFromPath( itemPath.getParent() ) )
                        .withVersion( itemPath.getName() ).build());
            } else {
                // We have to dig further and find the next directory with a pom
                Optional<StorageAsset> foundFile = StorageUtil.newAssetStream( itemPath )
                    .filter( a -> a.getName().toLowerCase().endsWith( ".pom" )
                        || a.getName().toLowerCase().startsWith( "maven-metadata" ) )
                    .findFirst( );
                if (foundFile.isPresent())
                {
                    int level = 0;
                    StorageAsset current = foundFile.get( );
                    while (current.hasParent() && !current.equals(itemPath)) {
                        level++;
                        current = current.getParent( );
                    }
                    // Project path if it is one level up from the found file
                    if (level==2) {
                        return projectMap.computeIfAbsent( itemPath,
                            myItemPath -> getProjectFromArtifactPath( foundFile.get( ) ) );
                    } else {
                        // All other paths are treated as namespace
                        return namespaceMap.computeIfAbsent( ns,
                            myNamespace -> ArchivaNamespace.withRepository( this )
                                .withAsset( itemPath )
                                .withNamespace( ns )
                                .build( ) );
                    }
                } else {
                    // Don't know what to do with it, so we treat it as namespace path
                    return namespaceMap.computeIfAbsent( ns,
                        myNamespace -> ArchivaNamespace.withRepository( this )
                            .withAsset( itemPath )
                            .withNamespace( ns )
                            .build( ) );
                }

            }
        }
    }

    // Simple object to hold artifact information
    private class ArtifactInfo  {
        private String id;
        private String version;
        private String extension;
        private String remainder;
        private String type;
        private String classifier;
        private String contentType;
    }

    private ArtifactInfo getArtifactInfoFromPath(String genericVersion, StorageAsset path) {
        final ArtifactInfo info = new ArtifactInfo( );
        info.id = path.getParent( ).getParent( ).getName( );
        final String fileName = path.getName( );
        if ( genericVersion.endsWith( "-" + SNAPSHOT ) )
        {
            String baseVersion = StringUtils.substringBeforeLast( genericVersion, "-" + SNAPSHOT );
            String prefix = info.id+"-"+baseVersion+"-";
            if (fileName.startsWith( prefix ))
            {
                String versionPostfix = StringUtils.removeStart( fileName, prefix );
                Matcher matcher = UNIQUE_SNAPSHOT_PATTERN.matcher( versionPostfix );
                if (matcher.matches()) {
                    info.version = baseVersion + "-" + matcher.group( 1 );
                    String newPrefix = prefix + info.version;
                    if (fileName.startsWith( newPrefix ))
                    {
                        String classPostfix = StringUtils.removeStart( fileName, newPrefix );
                        Matcher cMatch = CLASSIFIER_PATTERN.matcher( classPostfix );
                        if (cMatch.matches()) {
                            info.classifier = cMatch.group( 1 );
                            info.remainder = cMatch.group( 2 );
                        } else {
                            info.classifier = "";
                            info.remainder = classPostfix;
                        }
                    } else {
                        log.error( "Artifact does not match the maven name pattern {}", path );
                        info.classifier = "";
                        info.remainder = StringUtils.substringAfter( fileName, prefix );
                    }
                } else {
                    log.error( "Artifact does not match the snapshot version pattern {}", path );
                    info.version = "";
                    info.classifier = "";
                    info.remainder = StringUtils.substringAfter( fileName, prefix );
                }
            } else {
                log.error( "Artifact does not match the maven name pattern: {}", path );
                info.version = "";
                info.classifier = "";
                info.remainder = StringUtils.substringAfterLast( fileName, "." );
            }
        } else {
            String prefix = info.id+"-"+genericVersion;
            if (fileName.startsWith( prefix ))
            {
                info.version=genericVersion;
                String classPostfix = StringUtils.removeStart( fileName, prefix );
                Matcher cMatch = CLASSIFIER_PATTERN.matcher( classPostfix );
                if (cMatch.matches()) {
                    info.classifier = cMatch.group( 1 );
                    info.remainder = cMatch.group( 2 );
                } else {
                    info.classifier = "";
                    info.remainder = classPostfix;
                }
            } else {
                log.error( "Artifact does not match the version pattern {}", path );
                info.version = "";
                info.classifier = "";
                info.remainder = StringUtils.substringAfterLast( fileName, "." );
            }
        }
        info.extension = StringUtils.substringAfterLast( fileName, "." );
        info.type = MavenContentHelper.getTypeFromClassifierAndExtension( info.classifier, info.extension );
        try {
            info.contentType = Files.probeContentType( path.getFilePath( ) );
        } catch (IOException e) {
            info.contentType = "";
            //
        }
        return info;

    }

    @Override
    public Artifact getArtifact( final ItemSelector selector ) throws ContentAccessException
    {
        if (!selector.hasProjectId( )) {
            throw new IllegalArgumentException( "Project id must be set" );
        }
        if (!selector.hasVersion( )) {
            throw new IllegalArgumentException( "Version must be set" );
        }
        if (!selector.hasArtifactId( )) {
            throw new IllegalArgumentException( "Artifact Id must be set" );
        }
        final StorageAsset artifactDir = getAsset(selector.getNamespace(), selector.getProjectId(),
            selector.getVersion());
        final String artifactVersion = mavenContentHelper.getArtifactVersion( artifactDir, selector );
        final String classifier = MavenContentHelper.getClassifier( selector );
        final String extension = MavenContentHelper.getArtifactExtension( selector );
        final String fileName = MavenContentHelper.getArtifactFileName( selector, artifactVersion, classifier, extension );
        final StorageAsset path = getAsset( selector.getNamespace( ), selector.getProjectId( ),
            selector.getVersion( ), fileName );
        return artifactMap.computeIfAbsent( path, artifactPath -> createArtifact( path, selector, classifier, extension ) );
    }

    private StorageAsset getBasePathFromSelector(ItemSelector selector) {
        StringBuilder path = new StringBuilder( );
        if (selector.hasNamespace()) {
            path.append(String.join( "/", getNamespace( selector ).getNamespacePath( ) ));
        }
        if (selector.hasProjectId()) {
            path.append( "/" ).append( selector.getProjectId( ) );
        }
        if (selector.hasVersion()) {
            path.append( "/" ).append( selector.getVersion( ) );
        }
        return getStorage( ).getAsset( path.toString( ) );
    }

    /*
     * File filter to select certain artifacts using the selector data.
     */
    private Predicate<StorageAsset> getFileFilterFromSelector(final ItemSelector selector) {
        Predicate<StorageAsset> p = a -> a.isLeaf( );
        if (selector.hasArtifactId()) {
            final String pattern = selector.getArtifactId( );
            p = p.and( a -> StringUtils.startsWithIgnoreCase( a.getName( ),  pattern ) );
        }
        if (selector.hasArtifactVersion()) {
            final String pattern = selector.getArtifactVersion( );
            p = p.and( a -> StringUtils.containsIgnoreCase( a.getName( ),  pattern ) );
        }
        if (selector.hasExtension()) {
            final String pattern = "."+selector.getExtension( );
            p = p.and( a -> StringUtils.endsWithIgnoreCase( a.getName( ), pattern ) );
        } else if (selector.hasType()) {
            final String pattern = "."+ MavenContentHelper.getArtifactExtension( selector );
            p = p.and( a -> StringUtils.endsWithIgnoreCase( a.getName( ), pattern ) );
        }
        if (selector.hasClassifier()) {
            final String pattern = "-" + selector.getClassifier( ) + ".";
            p = p.and( a -> StringUtils.containsIgnoreCase( a.getName( ), pattern ) );
        } else if (selector.hasType()) {
            final String pattern = "-" + MavenContentHelper.getClassifierFromType( selector.getType( ) ) + ".";
            p = p.and( a -> StringUtils.containsIgnoreCase( a.getName( ).toLowerCase( ), pattern ) );
        }
        return p;
    }

    /*
        TBD
     */
    @Override
    public List<? extends Artifact> getAllArtifacts( ItemSelector selector ) throws ContentAccessException
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public Stream<? extends Artifact> getAllArtifactStream( ItemSelector selector ) throws ContentAccessException
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public List<? extends Project> getProjects( Namespace namespace )
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public List<? extends Version> getVersions( Project project )
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public List<? extends Artifact> getArtifacts( ContentItem item )
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public List<? extends Artifact> getArtifactsStartingWith( Namespace namespace )
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public Stream<? extends Artifact> getArtifactStream( ContentItem item )
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public Stream<? extends Artifact> getArtifactStreamStartingWith( Namespace namespace )
    {
        return null;
    }

    /*
        TBD
     */
    @Override
    public boolean hasContent( ItemSelector selector )
    {
        return false;
    }

    /*
        TBD
     */
    @Override
    public void copyArtifact( Path sourceFile, ItemSelector destination ) throws IllegalArgumentException
    {

    }

    @Override
    public void deleteVersion( VersionedReference ref ) throws ContentNotFoundException, ContentAccessException
    {
        final String path = toPath( ref );
        final Path deleteTarget = getRepoDir().resolve(path);
        if ( !Files.exists(deleteTarget) )
        {
            log.warn( "Version path for repository {} does not exist: {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Version not found for repository "+getId()+": "+path );
        }
        if ( Files.isDirectory(deleteTarget) )
        {
            try
            {
                org.apache.archiva.common.utils.FileUtils.deleteDirectory( deleteTarget );
            }
            catch ( IOException e )
            {
                log.error( "Could not delete file path {}: {}", deleteTarget, e.getMessage( ), e );
                throw new ContentAccessException( "Error while trying to delete path "+path+" from repository "+getId()+": "+e.getMessage( ), e );
            }
        } else {
            log.warn( "Version path for repository {} is not a directory {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Version path for repository "+getId()+" is not directory: " + path );
        }
    }

    @Override
    public void deleteProject( ProjectReference ref )
        throws ContentNotFoundException, ContentAccessException
    {
        final String path = toPath( ref );
        final Path deleteTarget = getRepoDir( ).resolve( path );
        if ( !Files.exists(deleteTarget) )
        {
            log.warn( "Project path for repository {} does not exist: {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Project not found for repository "+getId()+": "+path );
        }
        if ( Files.isDirectory(deleteTarget) )
        {
            try
            {
                org.apache.archiva.common.utils.FileUtils.deleteDirectory( deleteTarget );
            }
            catch ( IOException e )
            {
                log.error( "Could not delete file path {}: {}", deleteTarget, e.getMessage( ), e );
                throw new ContentAccessException( "Error while trying to delete path "+path+" from repository "+getId()+": "+e.getMessage( ), e );
            }
        }
        else
        {
            log.warn( "Project path for repository {} is not a directory {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Project path for repository "+getId()+" is not directory: " + path );
        }

    }

    @Override
    public void deleteProject( String namespace, String projectId ) throws ContentNotFoundException, ContentAccessException
    {
        this.deleteProject( new ProjectReference().groupId( namespace ).artifactId( projectId ) );
    }

    @Override
    public void deleteArtifact( ArtifactReference ref ) throws ContentNotFoundException, ContentAccessException
    {
        final String path = toPath( ref );
        final Path repoDir = getRepoDir( );
        Path deleteTarget = repoDir.resolve( path );
        if ( Files.exists(deleteTarget) )
        {
            try
            {
                if (Files.isDirectory( deleteTarget ))
                {
                    org.apache.archiva.common.utils.FileUtils.deleteDirectory( deleteTarget );
                } else {
                    Files.delete( deleteTarget );
                }
            }
            catch ( IOException e )
            {
                log.error( "Could not delete file path {}: {}", deleteTarget, e.getMessage( ), e );
                throw new ContentAccessException( "Error while trying to delete path "+path+" from repository "+getId()+": "+e.getMessage( ), e );
            }
        } else {
            log.warn( "Artifact path for repository {} does not exist: {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Artifact not found for repository "+getId()+": "+path );
        }

    }

    @Override
    public void deleteGroupId( String groupId )
        throws ContentNotFoundException, ContentAccessException
    {
        final String path = toPath( groupId );
        final Path deleteTarget = getRepoDir( ).resolve( path );
        if (!Files.exists(deleteTarget)) {
            log.warn( "Namespace path for repository {} does not exist: {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Namespace not found for repository "+getId()+": "+path );
        }
        if ( Files.isDirectory(deleteTarget) )
        {
            try
            {
                org.apache.archiva.common.utils.FileUtils.deleteDirectory( deleteTarget );
            }
            catch ( IOException e )
            {
                log.error( "Could not delete file path {}: {}", deleteTarget, e.getMessage( ), e );
                throw new ContentAccessException( "Error while trying to delete path "+path+" from repository "+getId()+": "+e.getMessage( ), e );
            }
        } else {
            log.warn( "Namespace path for repository {} is not a directory {}", getId(), deleteTarget );
            throw new ContentNotFoundException( "Namespace path for repository "+getId()+" is not directory: " + path );

        }
    }

    @Override
    public String getId()
    {
        return repository.getId();
    }

    @Override
    public List<ArtifactReference> getRelatedArtifacts( VersionedReference reference )
        throws ContentNotFoundException, LayoutException, ContentAccessException
    {
        StorageAsset artifactDir = toFile( reference );
        if ( !artifactDir.exists())
        {
            throw new ContentNotFoundException(
                "Unable to get related artifacts using a non-existant directory: " + artifactDir.getPath() );
        }

        if ( !artifactDir.isContainer() )
        {
            throw new ContentNotFoundException(
                "Unable to get related artifacts using a non-directory: " + artifactDir.getPath() );
        }

        // First gather up the versions found as artifacts in the managed repository.

        try (Stream<? extends StorageAsset> stream = artifactDir.list().stream() ) {
            return stream.filter(asset -> !asset.isContainer()).map(path -> {
                try {
                    ArtifactReference artifact = toArtifactReference(path.getPath());
                    if( artifact.getGroupId().equals( reference.getGroupId() ) && artifact.getArtifactId().equals(
                            reference.getArtifactId() ) && artifact.getVersion().equals( reference.getVersion() )) {
                        return artifact;
                    } else {
                        return null;
                    }
                } catch (LayoutException e) {
                    log.debug( "Not processing file that is not an artifact: {}", e.getMessage() );
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
        } catch (RuntimeException e) {
            Throwable cause = e.getCause( );
            if (cause!=null) {
                if (cause instanceof LayoutException) {
                    throw (LayoutException)cause;
                } else
                {
                    throw new ContentAccessException( cause.getMessage( ), cause );
                }
            } else {
                throw new ContentAccessException( e.getMessage( ), e );
            }
        }
    }

    /*
     * Create the filter for various combinations of classifier and type
     */
    private Predicate<ArtifactReference> getChecker(ArtifactReference referenceObject, String extension) {
        // TODO: Check, if extension is the correct parameter here
        // We compare type with extension which works for artifacts like .jar.md5 but may
        // be not the best way.

        if (referenceObject.getClassifier()!=null && referenceObject.getType()!=null) {
            return ((ArtifactReference a) ->
                referenceObject.getGroupId().equals( a.getGroupId() )
                && referenceObject.getArtifactId().equals( a.getArtifactId() )
                && referenceObject.getVersion( ).equals( a.getVersion( ) )
                && ( (a.getType()==null)
                    || referenceObject.getType().equals( a.getType() )
                    || a.getType().startsWith(extension) )
                && referenceObject.getClassifier().equals( a.getClassifier() )
            );
        } else if (referenceObject.getClassifier()!=null && referenceObject.getType()==null){
            return ((ArtifactReference a) ->
                referenceObject.getGroupId().equals( a.getGroupId() )
                    && referenceObject.getArtifactId().equals( a.getArtifactId() )
                    && referenceObject.getVersion( ).equals( a.getVersion( ) )
                    && referenceObject.getClassifier().equals( a.getClassifier() )
            );
        } else if (referenceObject.getClassifier()==null && referenceObject.getType()!=null){
            return ((ArtifactReference a) ->
                referenceObject.getGroupId().equals( a.getGroupId() )
                    && referenceObject.getArtifactId().equals( a.getArtifactId() )
                    && referenceObject.getVersion( ).equals( a.getVersion( ) )
                    && ( (a.getType()==null)
                    || referenceObject.getType().equals( a.getType() )
                    || a.getType().startsWith(extension) )
            );
        } else {
            return ((ArtifactReference a) ->
                referenceObject.getGroupId().equals( a.getGroupId() )
                    && referenceObject.getArtifactId().equals( a.getArtifactId() )
                    && referenceObject.getVersion( ).equals( a.getVersion( ) )
            );
        }


    }

    @Override
    public List<ArtifactReference> getRelatedArtifacts( ArtifactReference reference )
        throws ContentNotFoundException, LayoutException, ContentAccessException
    {
        if ( StringUtils.isEmpty( reference.getType() ) && StringUtils.isEmpty( reference.getClassifier() ) ) {
            return getRelatedArtifacts( toVersion( reference ) );
        }

        StorageAsset artifactFile = toFile( reference );
        StorageAsset repoDir = artifactFile.getParent();
        String ext;
        if (!artifactFile.isContainer()) {
            ext = StringUtils.substringAfterLast( artifactFile.getName(), ".");
        } else {
            ext = "";
        }

        if ( !repoDir.exists())
        {
            throw new ContentNotFoundException(
                "Unable to get related artifacts using a non-existant directory: " + repoDir.getPath() );
        }

        if ( !repoDir.isContainer() )
        {
            throw new ContentNotFoundException(
                "Unable to get related artifacts using a non-directory: " + repoDir.getPath() );
        }

        // First gather up the versions found as artifacts in the managed repository.

        try (Stream<? extends StorageAsset> stream = repoDir.list().stream() ) {
            return stream.filter(
                asset -> !asset.isContainer())
                .map(path -> {
                try {
                    return toArtifactReference(path.getPath());
                } catch (LayoutException e) {
                    log.debug( "Not processing file that is not an artifact: {}", e.getMessage() );
                    return null;
                }
            }).filter(Objects::nonNull).filter(getChecker( reference, ext )).collect(Collectors.toList());
        } catch (RuntimeException e) {
            Throwable cause = e.getCause( );
            if (cause!=null) {
                if (cause instanceof LayoutException) {
                    throw (LayoutException)cause;
                } else
                {
                    throw new ContentAccessException( cause.getMessage( ), cause );
                }
            } else {
                throw new ContentAccessException( e.getMessage( ), e );
            }
        }
    }

    @Override
    public List<StorageAsset> getRelatedAssets( ArtifactReference reference ) throws ContentNotFoundException, LayoutException, ContentAccessException
    {
        return null;
    }

    @Override
    public String getRepoRoot()
    {
        return convertUriToPath( repository.getLocation() );
    }

    private String convertUriToPath( URI uri ) {
        if (uri.getScheme()==null) {
            return Paths.get(uri.getPath()).toString();
        } else if ("file".equals(uri.getScheme())) {
            return Paths.get(uri).toString();
        } else {
            return uri.toString();
        }
    }

    @Override
    public ManagedRepository getRepository()
    {
        return repository;
    }

    /**
     * Gather the Available Versions (on disk) for a specific Project Reference, based on filesystem
     * information.
     *
     * @return the Set of available versions, based on the project reference.
     * @throws LayoutException
     */
    @Override
    public Set<String> getVersions( ProjectReference reference )
        throws ContentNotFoundException, LayoutException, ContentAccessException
    {
        final String path = toPath( reference );
        final Path projDir = getRepoDir().resolve(toPath(reference));
        if ( !Files.exists(projDir) )
        {
            throw new ContentNotFoundException(
                "Unable to get Versions on a non-existant directory for repository "+getId()+": " + path );
        }

        if ( !Files.isDirectory(projDir) )
        {
            throw new ContentNotFoundException(
                "Unable to get Versions on a non-directory for repository "+getId()+": " + path );
        }

        final String groupId = reference.getGroupId();
        final String artifactId = reference.getArtifactId();
        try(Stream<Path> stream = Files.list(projDir)) {
            return stream.filter(Files::isDirectory).map(
                    p -> toVersion(groupId, artifactId, p.getFileName().toString())
            ).filter(this::hasArtifact).map(ref -> ref.getVersion())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.error("Could not read directory {}: {}", projDir, e.getMessage(), e);
            throw new ContentAccessException( "Could not read path for repository "+getId()+": "+ path, e );
        } catch (RuntimeException e) {
            Throwable cause = e.getCause( );
            if (cause!=null)
            {
                if ( cause instanceof LayoutException )
                {
                    throw (LayoutException) cause;
                } else {
                    log.error("Could not read directory {}: {}", projDir, cause.getMessage(), cause);
                    throw new ContentAccessException( "Could not read path for repository "+getId()+": "+ path, cause );
                }
            } else {
                log.error("Could not read directory {}: {}", projDir, e.getMessage(), e);
                throw new ContentAccessException( "Could not read path for repository "+getId()+": "+ path, cause );
            }
        }
    }

    @Override
    public Set<String> getVersions( VersionedReference reference )
        throws ContentNotFoundException, ContentAccessException, LayoutException
    {
        try(Stream<ArtifactReference> stream = getArtifactStream( reference ))
        {
            return stream.filter( Objects::nonNull )
                .map( ar -> ar.getVersion( ) )
                .collect( Collectors.toSet( ) );
        } catch (IOException e) {
            final String path = toPath( reference );
            log.error("Could not read directory from repository {} - {}: ", getId(), path, e.getMessage(), e);
            throw new ContentAccessException( "Could not read path for repository "+getId()+": "+ path, e );
        }
    }

    @Override
    public boolean hasContent( ArtifactReference reference ) throws ContentAccessException
    {
        StorageAsset artifactFile = toFile( reference );
        return artifactFile.exists() && !artifactFile.isContainer();
    }

    @Override
    public boolean hasContent( ProjectReference reference ) throws ContentAccessException
    {
        try
        {
            Set<String> versions = getVersions( reference );
            return !versions.isEmpty();
        }
        catch ( ContentNotFoundException | LayoutException e )
        {
            return false;
        }
    }

    @Override
    public boolean hasContent( VersionedReference reference ) throws ContentAccessException
    {
        try
        {
            return ( getFirstArtifact( reference ) != null );
        }
        catch ( LayoutException | ContentNotFoundException e )
        {
            return false;
        }
        catch ( IOException e )
        {
            String path = toPath( reference );
            log.error("Could not read directory from repository {} - {}: ", getId(), path, e.getMessage(), e);
            throw new ContentAccessException( "Could not read path from repository " + getId( ) + ": " + path, e );
        }
    }

    @Override
    public void setRepository( final ManagedRepository repo )
    {
        this.repository = repo;
        if (repo!=null) {
            if (repository instanceof EditableManagedRepository) {
                ((EditableManagedRepository) repository).setContent(this);
            }
        }
    }

    private Path getRepoDir() {
        return repository.getAsset( "" ).getFilePath( );
    }

    private RepositoryStorage getStorage() {
        return repository.getAsset( "" ).getStorage( );
    }

    /**
     * Convert a path to an artifact reference.
     *
     * @param path the path to convert. (relative or full location path)
     * @throws LayoutException if the path cannot be converted to an artifact reference.
     */
    @Override
    public ArtifactReference toArtifactReference( String path )
        throws LayoutException
    {
        String repoPath = convertUriToPath( repository.getLocation() );
        if ( ( path != null ) && path.startsWith( repoPath ) && repoPath.length() > 0 )
        {
            return super.toArtifactReference( path.substring( repoPath.length() + 1 ) );
        } else {
            repoPath = path;
            if (repoPath!=null) {
                while (repoPath.startsWith("/")) {
                    repoPath = repoPath.substring(1);
                }
            }
            return super.toArtifactReference( repoPath );
        }
    }


    // The variant with runtime exception for stream usage
    private ArtifactReference toArtifactRef(String path) {
        try {
            return toArtifactReference(path);
        } catch (LayoutException e) {
            throw new RuntimeException(e);
        }
    }



    @Override
    public StorageAsset toFile( ArtifactReference reference )
    {
        return repository.getAsset(toPath(reference));
    }

    @Override
    public StorageAsset toFile( ArchivaArtifact reference )
    {
        return repository.getAsset( toPath( reference ) );
    }

    @Override
    public StorageAsset toFile( VersionedReference reference )
    {
        return repository.getAsset( toPath( reference ) );
    }

    /**
     * Get the first Artifact found in the provided VersionedReference location.
     *
     * @param reference the reference to the versioned reference to search within
     * @return the ArtifactReference to the first artifact located within the versioned reference. or null if
     *         no artifact was found within the versioned reference.
     * @throws java.io.IOException     if the versioned reference is invalid (example: doesn't exist, or isn't a directory)
     * @throws LayoutException
     */
    private ArtifactReference getFirstArtifact( VersionedReference reference )
        throws ContentNotFoundException, LayoutException, IOException
    {
        try(Stream<ArtifactReference> stream = getArtifactStream( reference ))
        {
            return stream.findFirst( ).orElse( null );
        } catch (RuntimeException e) {
            throw new ContentNotFoundException( e.getMessage( ), e.getCause( ) );
        }
    }

    private Stream<ArtifactReference> getArtifactStream(VersionedReference reference) throws ContentNotFoundException, LayoutException, IOException {
        final Path repoBase = getRepoDir( );
        String path = toMetadataPath( reference );
        Path versionDir = repoBase.resolve( path ).getParent();
        if ( !Files.exists(versionDir) )
        {
            throw new ContentNotFoundException( "Unable to gather the list of artifacts on a non-existant directory: "
                + versionDir.toAbsolutePath() );
        }

        if ( !Files.isDirectory(versionDir) )
        {
            throw new ContentNotFoundException(
                "Unable to gather the list of snapshot versions on a non-directory: " + versionDir.toAbsolutePath() );
        }
        return Files.list(versionDir).filter(Files::isRegularFile)
                .map(p -> repoBase.relativize(p).toString())
                .filter(p -> !filetypes.matchesDefaultExclusions(p))
                .filter(filetypes::matchesArtifactPattern)
                .map(this::toArtifactRef);
    }

    public List<ArtifactReference> getArtifacts(VersionedReference reference) throws ContentNotFoundException, LayoutException, ContentAccessException
    {
        try (Stream<ArtifactReference> stream = getArtifactStream( reference ))
        {
            return stream.collect( Collectors.toList( ) );
        } catch ( IOException e )
        {
            String path = toPath( reference );
            log.error("Could not read directory from repository {} - {}: ", getId(), path, e.getMessage(), e);
            throw new ContentAccessException( "Could not read path from repository " + getId( ) + ": " + path, e );

        }
    }

    private boolean hasArtifact( VersionedReference reference )

    {
        try(Stream<ArtifactReference> stream = getArtifactStream( reference ))
        {
            return stream.anyMatch( e -> true );
        } catch (ContentNotFoundException e) {
            return false;
        } catch ( LayoutException | IOException e) {
            // We throw the runtime exception for better stream handling
            throw new RuntimeException(e);
        }
    }

    public void setFiletypes( FileTypes filetypes )
    {
        this.filetypes = filetypes;
    }

    public void setMavenContentHelper( MavenContentHelper contentHelper) {
        this.mavenContentHelper = contentHelper;
    }


}
