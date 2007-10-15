package org.apache.maven.archiva.configuration;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.io.FileUtils;
import org.apache.maven.archiva.configuration.functors.ProxyConnectorConfigurationOrderComparator;
import org.apache.maven.archiva.configuration.io.registry.ConfigurationRegistryReader;
import org.apache.maven.archiva.configuration.io.registry.ConfigurationRegistryWriter;
import org.codehaus.plexus.evaluator.DefaultExpressionEvaluator;
import org.codehaus.plexus.evaluator.EvaluatorException;
import org.codehaus.plexus.evaluator.ExpressionEvaluator;
import org.codehaus.plexus.evaluator.sources.SystemPropertyExpressionSource;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.codehaus.plexus.registry.Registry;
import org.codehaus.plexus.registry.RegistryException;
import org.codehaus.plexus.registry.RegistryListener;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * Implementation of configuration holder that retrieves it from the registry.
 * </p>
 * <p>
 * The registry layers and merges the 2 configuration files: user, and application server.
 * </p>
 * <p>
 * Instead of relying on the model defaults, if the registry is empty a default configuration file is loaded and
 * applied from a resource. The defaults are not loaded into the registry as the lists (eg repositories) could no longer
 * be removed if that was the case.
 * </p>
 * <p>
 * When saving the configuration, it is saved to the location it was read from. If it was read from the defaults, it
 * will be saved to the user location.
 * However, if the configuration contains information from both sources, an exception is raised as this is currently
 * unsupported. The reason for this is that it is not possible to identify where to re-save elements, and can result
 * in list configurations (eg repositories) becoming inconsistent.
 * </p>
 * <p>
 * If the configuration is outdated, it will be upgraded when it is loaded. This is done by checking the version flag
 * before reading it from the registry.
 * </p>
 *
 * @plexus.component role="org.apache.maven.archiva.configuration.ArchivaConfiguration"
 */
public class DefaultArchivaConfiguration
    extends AbstractLogEnabled
    implements ArchivaConfiguration, RegistryListener, Initializable
{
    /**
     * Plexus registry to read the configuration from.
     *
     * @plexus.requirement role-hint="commons-configuration"
     */
    private Registry registry;

    /**
     * The configuration that has been converted.
     */
    private Configuration configuration;

    private static final String KEY = "org.apache.maven.archiva";

    /**
     * @plexus.configuration default-value="${user.home}/.m2/archiva.xml"
     */
    private String userConfigFilename;

    /**
     * @plexus.configuration default-value="${appserver.base}/conf/archiva.xml"
     */
    private String altConfigFilename;

    /**
     * Configuration Listeners we've registered.
     */
    private Set<ConfigurationListener> listeners = new HashSet<ConfigurationListener>();

    /**
     * Registry Listeners we've registered.
     */
    private Set<RegistryListener> registryListeners = new HashSet<RegistryListener>();
    
    /**
     * Boolean to help determine if the configuration exists as a result of pulling in
     * the default-archiva.xml
     */
    private boolean isConfigurationDefaulted = false;

    public synchronized Configuration getConfiguration()
    {
        if ( configuration == null )
        {
            configuration = load();
            configuration = processExpressions( configuration );
        }

        return configuration;
    }

    private Configuration load()
    {
        // TODO: should this be the same as section? make sure unnamed sections still work (eg, sys properties)
        Registry subset = registry.getSubset( KEY );
        if ( subset.getString( "version" ) == null )
        {
            // a little autodetection of v1, even if version is omitted (this was previously allowed)
            if ( subset.getSubset( "repositoryScanning" ).isEmpty() )
            {
                // only for empty, or v < 1
                subset = readDefaultConfiguration();
            }
        }

        Configuration config = new ConfigurationRegistryReader().read( subset );

        if ( !config.getRepositories().isEmpty() )
        {
            for ( Iterator<V1RepositoryConfiguration> i = config.getRepositories().iterator(); i.hasNext(); )
            {
                V1RepositoryConfiguration r = i.next();
                r.setScanned( r.isIndexed() );

                if ( r.getUrl().startsWith( "file://" ) )
                {
                    r.setLocation( r.getUrl().substring( 7 ) );
                    config.addManagedRepository( r );
                }
                else if ( r.getUrl().startsWith( "file:" ) )
                {
                    r.setLocation( r.getUrl().substring( 5 ) );
                    config.addManagedRepository( r );
                }
                else
                {
                    RemoteRepositoryConfiguration repo = new RemoteRepositoryConfiguration();
                    repo.setId( r.getId() );
                    repo.setLayout( r.getLayout() );
                    repo.setName( r.getName() );
                    repo.setUrl( r.getUrl() );
                    config.addRemoteRepository( repo );
                }
            }

            // Prevent duplicate repositories from showing up.
            config.getRepositories().clear();
        }

        // Normalize the order fields in the proxy connectors.
        if ( !config.getProxyConnectors().isEmpty() )
        {
            Map<String, java.util.List<ProxyConnectorConfiguration>> proxyConnectorMap = config
                .getProxyConnectorAsMap();

            for ( String key : proxyConnectorMap.keySet() )
            {
                List<ProxyConnectorConfiguration> connectors = proxyConnectorMap.get( key );
                // Sort connectors by order field.
                Collections.sort( connectors, ProxyConnectorConfigurationOrderComparator.getInstance() );

                // Normalize the order field values.
                int order = 1;
                for ( ProxyConnectorConfiguration connector : connectors )
                {
                    connector.setOrder( order++ );
                }
            }
        }

        return config;
    }

    private Registry readDefaultConfiguration()
    {
        // if it contains some old configuration, remove it (Archiva 0.9)
        registry.removeSubset( KEY );

        try
        {
            registry.addConfigurationFromResource( "org/apache/maven/archiva/configuration/default-archiva.xml", KEY );
            this.isConfigurationDefaulted = true;
        }
        catch ( RegistryException e )
        {
            throw new ConfigurationRuntimeException(
                                                     "Fatal error: Unable to find the built-in default configuration and load it into the registry",
                                                     e );
        }
        return registry.getSubset( KEY );
    }

    public synchronized void save( Configuration configuration )
        throws RegistryException, IndeterminateConfigurationException
    {
        Registry section = registry.getSection( KEY + ".user" );
        Registry baseSection = registry.getSection( KEY + ".base" );
        if ( section == null )
        {
            section = baseSection;
            if ( section == null )
            {
                section = createDefaultConfigurationFile();
            }
        }
        else if ( baseSection != null )
        {
            Collection<String> keys = baseSection.getKeys();
            boolean foundList = false;
            for ( Iterator<String> i = keys.iterator(); i.hasNext() && !foundList; )
            {
                String key = i.next();

                // a little aggressive with the repositoryScanning and databaseScanning - should be no need to split
                // that configuration
                if ( key.startsWith( "repositories" ) || key.startsWith( "proxyConnectors" )
                    || key.startsWith( "networkProxies" ) || key.startsWith( "repositoryScanning" )
                    || key.startsWith( "databaseScanning" ) || key.startsWith( "remoteRepositories" )
                    || key.startsWith( "managedRepositories" ) )
                {
                    foundList = true;
                }
            }

            if ( foundList )
            {
                this.configuration = null;

                throw new IndeterminateConfigurationException(
                                                               "Configuration can not be saved when it is loaded from two sources" );
            }
        }

        // escape all cron expressions to handle ','
        for ( Iterator<ManagedRepositoryConfiguration> i = configuration.getManagedRepositories().iterator(); i
            .hasNext(); )
        {
            ManagedRepositoryConfiguration c = i.next();
            c.setRefreshCronExpression( escapeCronExpression( c.getRefreshCronExpression() ) );
        }

        if ( configuration.getDatabaseScanning() != null )
        {
            configuration.getDatabaseScanning().setCronExpression(
                                                                   escapeCronExpression( configuration
                                                                       .getDatabaseScanning().getCronExpression() ) );
        }

        new ConfigurationRegistryWriter().write( configuration, section );
        section.save();

        triggerEvent( ConfigurationEvent.SAVED );

        this.configuration = processExpressions( configuration );
    }

    private Registry createDefaultConfigurationFile()
        throws RegistryException
    {
        // TODO: may not be needed under commons-configuration 1.4 - check
        // UPDATE: Upgrading to commons-configuration 1.4 breaks half the unit tests. 2007-10-11 (joakime)
        
        String contents = "<configuration />";
        if ( !writeFile( "user configuration", userConfigFilename, contents ) )
        {
            if ( !writeFile( "alternative configuration", altConfigFilename, contents ) )
            {
                throw new RegistryException( "Unable to create configuration file in either user ["
                    + userConfigFilename + "] or alternative [" + altConfigFilename
                    + "] locations on disk, usually happens when not allowed to write to those locations." );
            }
        }

        try
        {
            ( (Initializable) registry ).initialize();

            for ( RegistryListener regListener : registryListeners )
            {
                addRegistryChangeListener( regListener );
            }
        }
        catch ( InitializationException e )
        {
            throw new RegistryException( "Unable to reinitialize configuration: " + e.getMessage(), e );
        }

        triggerEvent( ConfigurationEvent.SAVED );

        return registry.getSection( KEY + ".user" );
    }

    /**
     * Attempts to write the contents to a file, if an IOException occurs, return false.
     * 
     * @param filetype the filetype (freeform text) to use in logging messages when failure to write.
     * @param path the path to write to.
     * @param contents the contents to write.
     * @return true if write successful.
     */
    private boolean writeFile( String filetype, String path, String contents )
    {
        File file = new File( path );

        try
        {
            FileUtils.writeStringToFile( file, contents, "UTF-8" );
            return true;
        }
        catch ( IOException e )
        {
            getLogger().error( "Unable to create " + filetype + " file: " + e.getMessage(), e );
            return false;
        }
    }

    private void triggerEvent( int type )
    {
        ConfigurationEvent evt = new ConfigurationEvent( type );
        for ( ConfigurationListener listener : listeners )
        {
            try
            {
                listener.configurationEvent( evt );
            }
            catch ( Throwable t )
            {
                getLogger().warn( "Unable to notify of saved configuration event.", t );
            }
        }
    }

    public void addListener( ConfigurationListener listener )
    {
        if ( listener == null )
        {
            return;
        }

        listeners.add( listener );
    }

    public void removeListener( ConfigurationListener listener )
    {
        if ( listener == null )
        {
            return;
        }

        listeners.remove( listener );
    }

    public void addChangeListener( RegistryListener listener )
    {
        addRegistryChangeListener( listener );

        // keep track for later
        registryListeners.add( listener );
    }

    private void addRegistryChangeListener( RegistryListener listener )
    {
        Registry section = registry.getSection( KEY + ".user" );
        if ( section != null )
        {
            section.addChangeListener( listener );
        }
        section = registry.getSection( KEY + ".base" );
        if ( section != null )
        {
            section.addChangeListener( listener );
        }
    }

    public void initialize()
        throws InitializationException
    {
        // Resolve expressions in the userConfigFilename and altConfigFilename
        try
        {
            ExpressionEvaluator expressionEvaluator = new DefaultExpressionEvaluator();
            expressionEvaluator.addExpressionSource( new SystemPropertyExpressionSource() );
            userConfigFilename = expressionEvaluator.expand( userConfigFilename );
            altConfigFilename = expressionEvaluator.expand( altConfigFilename );
        }
        catch ( EvaluatorException e )
        {
            throw new InitializationException( "Unable to evaluate expressions found in "
                + "userConfigFilename or altConfigFilename." );
        }

        registry.addChangeListener( this );
    }

    public void beforeConfigurationChange( Registry registry, String propertyName, Object propertyValue )
    {
        // nothing to do here
    }

    public synchronized void afterConfigurationChange( Registry registry, String propertyName, Object propertyValue )
    {
        configuration = null;
    }

    private String removeExpressions( String directory )
    {
        String value = StringUtils.replace( directory, "${appserver.base}", registry.getString( "appserver.base",
                                                                                                "${appserver.base}" ) );
        value = StringUtils.replace( value, "${appserver.home}", registry.getString( "appserver.home",
                                                                                     "${appserver.home}" ) );
        return value;
    }

    private String unescapeCronExpression( String cronExpression )
    {
        return StringUtils.replace( cronExpression, "\\,", "," );
    }

    private String escapeCronExpression( String cronExpression )
    {
        return StringUtils.replace( cronExpression, ",", "\\," );
    }

    private Configuration processExpressions( Configuration config )
    {
        // TODO: for commons-configuration 1.3 only
        for ( Iterator<ManagedRepositoryConfiguration> i = config.getManagedRepositories().iterator(); i.hasNext(); )
        {
            ManagedRepositoryConfiguration c = i.next();
            c.setLocation( removeExpressions( c.getLocation() ) );
            c.setRefreshCronExpression( unescapeCronExpression( c.getRefreshCronExpression() ) );
        }

        DatabaseScanningConfiguration databaseScanning = config.getDatabaseScanning();
        if ( databaseScanning != null )
        {
            String cron = databaseScanning.getCronExpression();
            databaseScanning.setCronExpression( unescapeCronExpression( cron ) );
        }

        return config;
    }

    public String getUserConfigFilename()
    {
        return userConfigFilename;
    }

    public String getAltConfigFilename()
    {
        return altConfigFilename;
    }

    public boolean isDefaulted()
    {
        return this.isConfigurationDefaulted;
    }
}
