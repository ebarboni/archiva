package org.apache.maven.archiva.database.jdo;

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

import org.apache.maven.archiva.database.ArchivaDatabaseException;
import org.apache.maven.archiva.database.ArtifactDAO;
import org.apache.maven.archiva.database.Constraint;
import org.apache.maven.archiva.database.ObjectNotFoundException;
import org.apache.maven.archiva.model.ArchivaArtifact;

import java.util.List;

/**
 * JdoArtifactDAO 
 *
 * @author <a href="mailto:joakim@erdfelt.com">Joakim Erdfelt</a>
 * @version $Id$
 * 
 * @plexus.component role-hint="jdo"
 */
public class JdoArtifactDAO
implements ArtifactDAO
{
    /**
     * @plexus.requirement role-hint="default"
     */
    private JdoAccess jdo;

    /* .\ Archiva Artifact \. _____________________________________________________________ */

    public ArchivaArtifact createArtifact( String groupId, String artifactId, String version, String classifier, String type )
    {
        ArchivaArtifact artifact;

        try
        {
            artifact = getArtifact( groupId, artifactId, version, classifier, type );
        }
        catch ( ArchivaDatabaseException e )
        {
            artifact = new ArchivaArtifact( groupId, artifactId, version, classifier, type );
        }

        return artifact;
    }

    public ArchivaArtifact getArtifact( String groupId, String artifactId, String version, String classifier, String type )
        throws ObjectNotFoundException, ArchivaDatabaseException
    {
        
        return null;
    }

    public List queryArtifacts( Constraint constraint )
        throws ObjectNotFoundException, ArchivaDatabaseException
    {
        // TODO Auto-generated method stub
        return null;
    }

    public ArchivaArtifact saveArtifact( ArchivaArtifact artifact )
        throws ArchivaDatabaseException
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void deleteArtifact( ArchivaArtifact artifact )
        throws ArchivaDatabaseException
    {
        // TODO Auto-generated method stub

    }

}
