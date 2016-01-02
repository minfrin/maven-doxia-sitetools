package org.apache.maven.doxia.tools;

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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.doxia.site.decoration.Banner;
import org.apache.maven.doxia.site.decoration.DecorationModel;
import org.apache.maven.doxia.site.decoration.Menu;
import org.apache.maven.doxia.site.decoration.MenuItem;
import org.apache.maven.doxia.site.decoration.Skin;
import org.apache.maven.doxia.site.decoration.inheritance.DecorationModelInheritanceAssembler;
import org.apache.maven.doxia.site.decoration.io.xpp3.DecorationXpp3Reader;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Site;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.reporting.MavenReport;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.i18n.I18N;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.interpolation.EnvarBasedValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Default implementation of the site tool.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 */
@Component( role = SiteTool.class )
public class DefaultSiteTool
    extends AbstractLogEnabled
    implements SiteTool
{
    // ----------------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------------

    /**
     * The component that is used to resolve additional artifacts required.
     */
    @Requirement
    private ArtifactResolver artifactResolver;

    /**
     * The component used for creating artifact instances.
     */
    @Requirement
    private ArtifactFactory artifactFactory;

    /**
     * Internationalization.
     */
    @Requirement
    protected I18N i18n;

    /**
     * The component for assembling inheritance.
     */
    @Requirement
    protected DecorationModelInheritanceAssembler assembler;

    /**
     * Project builder.
     */
    @Requirement
    protected MavenProjectBuilder mavenProjectBuilder;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    public Artifact getSkinArtifactFromRepository( ArtifactRepository localRepository,
                                                   List<ArtifactRepository> remoteArtifactRepositories,
                                                   DecorationModel decoration )
        throws SiteToolException
    {
        checkNotNull( "localRepository", localRepository );
        checkNotNull( "remoteArtifactRepositories", remoteArtifactRepositories );
        checkNotNull( "decoration", decoration );

        Skin skin = decoration.getSkin();

        if ( skin == null )
        {
            skin = Skin.getDefaultSkin();
        }

        String version = skin.getVersion();
        Artifact artifact;
        try
        {
            if ( version == null )
            {
                version = Artifact.RELEASE_VERSION;
            }
            VersionRange versionSpec = VersionRange.createFromVersionSpec( version );
            artifact = artifactFactory.createDependencyArtifact( skin.getGroupId(), skin.getArtifactId(), versionSpec,
                                                                 "jar", null, null );

            artifactResolver.resolve( artifact, remoteArtifactRepositories, localRepository );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new SiteToolException( "InvalidVersionSpecificationException: The skin version '" + version
                + "' is not valid: " + e.getMessage(), e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new SiteToolException( "ArtifactResolutionException: Unable to find skin", e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new SiteToolException( "ArtifactNotFoundException: The skin does not exist: " + e.getMessage(), e );
        }

        return artifact;
    }

    /** {@inheritDoc} */
    public Artifact getDefaultSkinArtifact( ArtifactRepository localRepository,
                                            List<ArtifactRepository> remoteArtifactRepositories )
        throws SiteToolException
    {
        return getSkinArtifactFromRepository( localRepository, remoteArtifactRepositories, new DecorationModel() );
    }

    /** {@inheritDoc} */
    public String getRelativePath( String to, String from )
    {
        checkNotNull( "to", to );
        checkNotNull( "from", from );

        URL toUrl = null;
        URL fromUrl = null;

        String toPath = to;
        String fromPath = from;

        try
        {
            toUrl = new URL( to );
        }
        catch ( MalformedURLException e )
        {
            try
            {
                toUrl = new File( getNormalizedPath( to ) ).toURI().toURL();
            }
            catch ( MalformedURLException e1 )
            {
                getLogger().warn( "Unable to load a URL for '" + to + "': " + e.getMessage() );
            }
        }

        try
        {
            fromUrl = new URL( from );
        }
        catch ( MalformedURLException e )
        {
            try
            {
                fromUrl = new File( getNormalizedPath( from ) ).toURI().toURL();
            }
            catch ( MalformedURLException e1 )
            {
                getLogger().warn( "Unable to load a URL for '" + from + "': " + e.getMessage() );
            }
        }

        if ( toUrl != null && fromUrl != null )
        {
            // URLs, determine if they share protocol and domain info

            if ( ( toUrl.getProtocol().equalsIgnoreCase( fromUrl.getProtocol() ) )
                && ( toUrl.getHost().equalsIgnoreCase( fromUrl.getHost() ) )
                && ( toUrl.getPort() == fromUrl.getPort() ) )
            {
                // shared URL domain details, use URI to determine relative path

                toPath = toUrl.getFile();
                fromPath = fromUrl.getFile();
            }
            else
            {
                // don't share basic URL information, no relative available

                return to;
            }
        }
        else if ( ( toUrl != null && fromUrl == null ) || ( toUrl == null && fromUrl != null ) )
        {
            // one is a URL and the other isn't, no relative available.

            return to;
        }

        // either the two locations are not URLs or if they are they
        // share the common protocol and domain info and we are left
        // with their URI information

        String relativePath = getRelativeFilePath( fromPath, toPath );

        if ( relativePath == null )
        {
            relativePath = to;
        }

        if ( getLogger().isDebugEnabled() && !relativePath.toString().equals( to ) )
        {
            getLogger().debug( "Mapped url: " + to + " to relative path: " + relativePath );
        }

        return relativePath;
    }

    private static String getRelativeFilePath( final String oldPath, final String newPath )
    {
        // normalize the path delimiters

        String fromPath = new File( oldPath ).getPath();
        String toPath = new File( newPath ).getPath();

        // strip any leading slashes if its a windows path
        if ( toPath.matches( "^\\[a-zA-Z]:" ) )
        {
            toPath = toPath.substring( 1 );
        }
        if ( fromPath.matches( "^\\[a-zA-Z]:" ) )
        {
            fromPath = fromPath.substring( 1 );
        }

        // lowercase windows drive letters.
        if ( fromPath.startsWith( ":", 1 ) )
        {
            fromPath = Character.toLowerCase( fromPath.charAt( 0 ) ) + fromPath.substring( 1 );
        }
        if ( toPath.startsWith( ":", 1 ) )
        {
            toPath = Character.toLowerCase( toPath.charAt( 0 ) ) + toPath.substring( 1 );
        }

        // check for the presence of windows drives. No relative way of
        // traversing from one to the other.

        if ( ( toPath.startsWith( ":", 1 ) && fromPath.startsWith( ":", 1 ) )
            && ( !toPath.substring( 0, 1 ).equals( fromPath.substring( 0, 1 ) ) ) )
        {
            // they both have drive path element but they don't match, no
            // relative path

            return null;
        }

        if ( ( toPath.startsWith( ":", 1 ) && !fromPath.startsWith( ":", 1 ) )
            || ( !toPath.startsWith( ":", 1 ) && fromPath.startsWith( ":", 1 ) ) )
        {

            // one has a drive path element and the other doesn't, no relative
            // path.

            return null;

        }

        final String relativePath = buildRelativePath( toPath, fromPath, File.separatorChar );

        return relativePath.toString();
    }

    /** {@inheritDoc} */
    public File getSiteDescriptor( File siteDirectory, Locale locale )
    {
        checkNotNull( "siteDirectory", siteDirectory );
        final Locale llocale = ( locale == null ) ? new Locale( "" ) : locale;

        File siteDescriptor = new File( siteDirectory, "site_" + llocale.getLanguage() + ".xml" );

        if ( !siteDescriptor.isFile() )
        {
            siteDescriptor = new File( siteDirectory, "site.xml" );
        }
        return siteDescriptor;
    }

    /**
     * Get a site descriptor from one of the repositories.
     *
     * @param project the Maven project, not null.
     * @param localRepository the Maven local repository, not null.
     * @param repositories the Maven remote repositories, not null.
     * @param locale the locale wanted for the site descriptor. If not null, searching for
     * <code>site_<i>localeLanguage</i>.xml</code>, otherwise searching for <code>site.xml</code>.
     * @return the site descriptor into the local repository after download of it from repositories or null if not
     * found in repositories.
     * @throws SiteToolException if any
     */
    File getSiteDescriptorFromRepository( MavenProject project, ArtifactRepository localRepository,
                                                 List<ArtifactRepository> repositories, Locale locale )
        throws SiteToolException
    {
        checkNotNull( "project", project );
        checkNotNull( "localRepository", localRepository );
        checkNotNull( "repositories", repositories );

        final Locale llocale = ( locale == null ) ? new Locale( "" ) : locale;

        try
        {
            return resolveSiteDescriptor( project, localRepository, repositories, llocale );
        }
        catch ( ArtifactNotFoundException e )
        {
            getLogger().debug( "ArtifactNotFoundException: Unable to locate site descriptor: " + e );
            return null;
        }
        catch ( ArtifactResolutionException e )
        {
            throw new SiteToolException( "ArtifactResolutionException: Unable to locate site descriptor: "
                + e.getMessage(), e );
        }
        catch ( IOException e )
        {
            throw new SiteToolException( "IOException: Unable to locate site descriptor: " + e.getMessage(), e );
        }
    }

    /** {@inheritDoc} */
    public DecorationModel getDecorationModel( File siteDirectory, Locale locale, MavenProject project,
                                               List<MavenProject> reactorProjects, ArtifactRepository localRepository,
                                               List<ArtifactRepository> repositories )
        throws SiteToolException
    {
        checkNotNull( "project", project );
        checkNotNull( "reactorProjects", reactorProjects );
        checkNotNull( "localRepository", localRepository );
        checkNotNull( "repositories", repositories );

        final Locale llocale = ( locale == null ) ? Locale.getDefault() : locale;

        getLogger().debug( "Computing decoration model for locale " + llocale );

        Map<String, String> props = new HashMap<String, String>( 2 );

        // This is to support the deprecated ${reports} and ${modules} tags.
        props.put( "reports", "<menu ref=\"reports\"/>\n" );
        props.put( "modules", "<menu ref=\"modules\"/>\n" );

        MavenProject parentProject = getParentProject( project, reactorProjects, localRepository );

        DecorationModel decorationModel = getDecorationModel( 0, siteDirectory, llocale, project, parentProject,
                                                              reactorProjects, localRepository, repositories, props );

        if ( decorationModel == null )
        {
            getLogger().debug( "Using default site descriptor" );

            String siteDescriptorContent;

            InputStream in = null;
            try
            {
                // Note the default is not a super class - it is used when nothing else is found
                in = getClass().getResourceAsStream( "/default-site.xml" );
                siteDescriptorContent = IOUtil.toString( in, "UTF-8" );
            }
            catch ( IOException e )
            {
                throw new SiteToolException( "Error reading default site descriptor: " + e.getMessage(), e );
            }
            finally
            {
                IOUtil.close( in );
            }

            siteDescriptorContent = getInterpolatedSiteDescriptorContent( props, project, siteDescriptorContent );

            decorationModel = readDecorationModel( siteDescriptorContent );
        }

        if ( parentProject != null )
        {
            populateParentMenu( decorationModel, llocale, project, parentProject, true );
        }

        populateModulesMenu( decorationModel, llocale, project, reactorProjects, localRepository, true );

        if ( decorationModel.getBannerLeft() == null )
        {
            // extra default to set
            Banner banner = new Banner();
            banner.setName( project.getName() );
            decorationModel.setBannerLeft( banner );
        }

        return decorationModel;
    }

    /** {@inheritDoc} */
    public String getInterpolatedSiteDescriptorContent( Map<String, String> props, MavenProject aProject,
                                                        String siteDescriptorContent )
        throws SiteToolException
    {
        checkNotNull( "props", props );
        checkNotNull( "aProject", aProject );
        checkNotNull( "siteDescriptorContent", siteDescriptorContent );

        // MSITE-201: The ObjectBasedValueSource( aProject ) below will match
        // ${modules} to aProject.getModules(), so we need to interpolate that
        // first.

        Map<String, String> modulesProps = new HashMap<String, String>( 1 );

        // Legacy for the old ${modules} syntax
        modulesProps.put( "modules", "<menu ref=\"modules\"/>" );

        String interpolatedSiteDescriptorContent = StringUtils.interpolate( siteDescriptorContent, modulesProps );

        RegexBasedInterpolator interpolator = new RegexBasedInterpolator();

        try
        {
            interpolator.addValueSource( new EnvarBasedValueSource() );
        }
        catch ( IOException e )
        {
            // Prefer logging?
            throw new SiteToolException( "IOException: cannot interpolate environment properties: " + e.getMessage(),
                                         e );
        }

        interpolator.addValueSource( new ObjectBasedValueSource( aProject ) );

        interpolator.addValueSource( new MapBasedValueSource( aProject.getProperties() ) );

        try
        {
            // FIXME: this does not escape xml entities, see MSITE-226, PLXCOMP-118
            interpolatedSiteDescriptorContent = interpolator.interpolate( interpolatedSiteDescriptorContent,
                                                                          "project" );
        }
        catch ( InterpolationException e )
        {
            throw new SiteToolException( "Cannot interpolate site descriptor: " + e.getMessage(), e );
        }

        // Legacy for the old ${parentProject} syntax
        props.put( "parentProject", "<menu ref=\"parent\"/>" );

        // Legacy for the old ${reports} syntax
        props.put( "reports", "<menu ref=\"reports\"/>" );

        return StringUtils.interpolate( interpolatedSiteDescriptorContent, props );
    }

    /** {@inheritDoc} */
    public MavenProject getParentProject( MavenProject aProject, List<MavenProject> reactorProjects,
                                          ArtifactRepository localRepository )
    {
        checkNotNull( "aProject", aProject );
        checkNotNull( "reactorProjects", reactorProjects );
        checkNotNull( "localRepository", localRepository );

        MavenProject parentProject = null;

        MavenProject origParent = aProject.getParent();
        if ( origParent != null )
        {
            for ( MavenProject reactorProject : reactorProjects )
            {
                if ( reactorProject.getGroupId().equals( origParent.getGroupId() )
                    && reactorProject.getArtifactId().equals( origParent.getArtifactId() )
                    && reactorProject.getVersion().equals( origParent.getVersion() ) )
                {
                    parentProject = reactorProject;

                    getLogger().debug( "Parent project " + origParent.getId() + " picked from reactor" );
                    break;
                }
            }

            if ( parentProject == null && aProject.getBasedir() != null
                && StringUtils.isNotEmpty( aProject.getModel().getParent().getRelativePath() ) )
            {
                try
                {
                    String relativePath = aProject.getModel().getParent().getRelativePath();

                    File pomFile = new File( aProject.getBasedir(), relativePath );

                    if ( pomFile.isDirectory() )
                    {
                        pomFile = new File( pomFile, "pom.xml" );
                    }
                    pomFile = new File( getNormalizedPath( pomFile.getPath() ) );

                    if ( pomFile.isFile() )
                    {
                        MavenProject mavenProject = mavenProjectBuilder.build( pomFile, localRepository, null );

                        if ( mavenProject.getGroupId().equals( origParent.getGroupId() )
                            && mavenProject.getArtifactId().equals( origParent.getArtifactId() )
                            && mavenProject.getVersion().equals( origParent.getVersion() ) )
                        {
                            parentProject = mavenProject;

                            getLogger().debug( "Parent project " + origParent.getId() + " loaded from a relative path: "
                                + relativePath );
                        }
                    }
                }
                catch ( ProjectBuildingException e )
                {
                    getLogger().info( "Unable to load parent project " + origParent.getId() + " from a relative path: "
                        + e.getMessage() );
                }
            }

            if ( parentProject == null )
            {
                try
                {
                    parentProject = mavenProjectBuilder.buildFromRepository( aProject.getParentArtifact(), aProject
                        .getRemoteArtifactRepositories(), localRepository );

                    getLogger().debug( "Parent project " + origParent.getId() + " loaded from repository" );
                }
                catch ( ProjectBuildingException e )
                {
                    getLogger().warn( "Unable to load parent project " + origParent.getId() + " from repository: "
                        + e.getMessage() );
                }
            }

            if ( parentProject == null )
            {
                // fallback to original parent, which may contain uninterpolated value (still need a unit test)

                parentProject = origParent;

                getLogger().debug( "Parent project " + origParent.getId() + " picked from original value" );
            }
        }
        return parentProject;
    }

    /**
     * Populate the pre-defined <code>parent</code> menu of the decoration model,
     * if used through <code>&lt;menu ref="parent"/&gt;</code>.
     *
     * @param decorationModel the Doxia Sitetools DecorationModel, not null.
     * @param locale the locale used for the i18n in DecorationModel. If null, using the default locale in the jvm.
     * @param project a Maven project, not null.
     * @param parentProject a Maven parent project, not null.
     * @param keepInheritedRefs used for inherited references.
     */
    private void populateParentMenu( DecorationModel decorationModel, Locale locale, MavenProject project,
                                    MavenProject parentProject, boolean keepInheritedRefs )
    {
        checkNotNull( "decorationModel", decorationModel );
        checkNotNull( "project", project );
        checkNotNull( "parentProject", parentProject );

        Menu menu = decorationModel.getMenuRef( "parent" );

        if ( menu == null )
        {
            return;
        }

        if ( keepInheritedRefs && menu.isInheritAsRef() )
        {
            return;
        }

        final Locale llocale = ( locale == null ) ? Locale.getDefault() : locale;

        String parentUrl = getDistMgmntSiteUrl( parentProject );

        if ( parentUrl != null )
        {
            if ( parentUrl.endsWith( "/" ) )
            {
                parentUrl += "index.html";
            }
            else
            {
                parentUrl += "/index.html";
            }

            parentUrl = getRelativePath( parentUrl, getDistMgmntSiteUrl( project ) );
        }
        else
        {
            // parent has no url, assume relative path is given by site structure
            File parentBasedir = parentProject.getBasedir();
            // First make sure that the parent is available on the file system
            if ( parentBasedir != null )
            {
                // Try to find the relative path to the parent via the file system
                String parentPath = parentBasedir.getAbsolutePath();
                String projectPath = project.getBasedir().getAbsolutePath();
                parentUrl = getRelativePath( parentPath, projectPath ) + "/index.html";
            }
        }

        // Only add the parent menu if we were able to find a URL for it
        if ( parentUrl == null )
        {
            getLogger().warn( "Unable to find a URL to the parent project. The parent menu will NOT be added." );
        }
        else
        {
            if ( menu.getName() == null )
            {
                menu.setName( i18n.getString( "site-tool", llocale, "decorationModel.menu.parentproject" ) );
            }

            MenuItem item = new MenuItem();
            item.setName( parentProject.getName() );
            item.setHref( parentUrl );
            menu.addItem( item );
        }
    }

    /**
     * Populate the pre-defined <code>modules</code> menu of the decoration model,
     * if used through <code>&lt;menu ref="modules"/&gt;</code>.
     *
     * @param decorationModel the Doxia Sitetools DecorationModel, not null.
     * @param locale the locale used for the i18n in DecorationModel. If null, using the default locale in the jvm.
     * @param project a Maven project, not null.
     * @param reactorProjects the Maven reactor projects, not null.
     * @param localRepository the Maven local repository, not null.
     * @param keepInheritedRefs used for inherited references.
     * @throws SiteToolException if any
     */
    private void populateModulesMenu( DecorationModel decorationModel, Locale locale, MavenProject project,
                                     List<MavenProject> reactorProjects, ArtifactRepository localRepository,
                                     boolean keepInheritedRefs )
        throws SiteToolException
    {
        checkNotNull( "project", project );
        checkNotNull( "reactorProjects", reactorProjects );
        checkNotNull( "localRepository", localRepository );
        checkNotNull( "decorationModel", decorationModel );

        Menu menu = decorationModel.getMenuRef( "modules" );

        if ( menu == null )
        {
            return;
        }

        if ( keepInheritedRefs && menu.isInheritAsRef() )
        {
            return;
        }

        final Locale llocale = ( locale == null ) ? Locale.getDefault() : locale ;

        // we require child modules and reactors to process module menu
        if ( project.getModules().size() > 0 )
        {
            if ( menu.getName() == null )
            {
                menu.setName( i18n.getString( "site-tool", llocale, "decorationModel.menu.projectmodules" ) );
            }

            getLogger().debug( "Attempting to load module information from local filesystem" );

            // Not running reactor - search for the projects manually
            List<Model> models = new ArrayList<Model>( project.getModules().size() );
            for ( String module : (List<String>) project.getModules() )
            {
                Model model;
                File f = new File( project.getBasedir(), module + "/pom.xml" );
                if ( f.exists() )
                {
                    try
                    {
                        model = mavenProjectBuilder.build( f, localRepository, null ).getModel();
                    }
                    catch ( ProjectBuildingException e )
                    {
                        throw new SiteToolException( "Unable to read local module-POM", e );
                    }
                }
                else
                {
                    getLogger().warn( "No filesystem module-POM available" );

                    model = new Model();
                    model.setName( module );
                    setDistMgmntSiteUrl( model, module );
                }
                models.add( model );
            }
            populateModulesMenuItemsFromModels( project, models, menu );
        }
        else if ( decorationModel.getMenuRef( "modules" ).getInherit() == null )
        {
            // only remove if project has no modules AND menu is not inherited, see MSHARED-174
            decorationModel.removeMenuRef( "modules" );
        }
    }

    /** {@inheritDoc} */
    public void populateReportsMenu( DecorationModel decorationModel, Locale locale,
                                     Map<String, List<MavenReport>> categories )
    {
        checkNotNull( "decorationModel", decorationModel );
        checkNotNull( "categories", categories );

        Menu menu = decorationModel.getMenuRef( "reports" );

        if ( menu == null )
        {
            return;
        }

        final Locale llocale = ( locale == null ) ? Locale.getDefault() : locale;

        if ( menu.getName() == null )
        {
            menu.setName( i18n.getString( "site-tool", llocale, "decorationModel.menu.projectdocumentation" ) );
        }

        boolean found = false;
        if ( menu.getItems().isEmpty() )
        {
            List<MavenReport> categoryReports = categories.get( MavenReport.CATEGORY_PROJECT_INFORMATION );
            if ( !isEmptyList( categoryReports ) )
            {
                MenuItem item = createCategoryMenu(
                                                    i18n.getString( "site-tool", llocale,
                                                                    "decorationModel.menu.projectinformation" ),
                                                    "/project-info.html", categoryReports, llocale );
                menu.getItems().add( item );
                found = true;
            }

            categoryReports = categories.get( MavenReport.CATEGORY_PROJECT_REPORTS );
            if ( !isEmptyList( categoryReports ) )
            {
                MenuItem item =
                    createCategoryMenu( i18n.getString( "site-tool", llocale, "decorationModel.menu.projectreports" ),
                                        "/project-reports.html", categoryReports, llocale );
                menu.getItems().add( item );
                found = true;
            }
        }
        if ( !found )
        {
            decorationModel.removeMenuRef( "reports" );
        }
    }

    /** {@inheritDoc} */
    public List<Locale> getSiteLocales( String locales )
    {
        if ( locales == null )
        {
            return Collections.singletonList( DEFAULT_LOCALE );
        }

        String[] localesArray = StringUtils.split( locales, "," );
        List<Locale> localesList = new ArrayList<Locale>( localesArray.length );

        for ( String localeString : localesArray )
        {
            Locale locale = codeToLocale( localeString );

            if ( locale == null )
            {
                continue;
            }

            if ( !Arrays.asList( Locale.getAvailableLocales() ).contains( locale ) )
            {
                if ( getLogger().isWarnEnabled() )
                {
                    getLogger().warn( "The locale defined by '" + locale
                        + "' is not available in this Java Virtual Machine ("
                        + System.getProperty( "java.version" )
                        + " from " + System.getProperty( "java.vendor" ) + ") - IGNORING" );
                }
                continue;
            }

            // Default bundles are in English
            if ( ( !locale.getLanguage().equals( DEFAULT_LOCALE.getLanguage() ) )
                && ( !i18n.getBundle( "site-tool", locale ).getLocale().getLanguage()
                    .equals( locale.getLanguage() ) ) )
            {
                if ( getLogger().isWarnEnabled() )
                {
                    getLogger().warn( "The locale '" + locale + "' (" + locale.getDisplayName( Locale.ENGLISH )
                        + ") is not currently supported by Maven Site - IGNORING."
                        + "\nContributions are welcome and greatly appreciated!"
                        + "\nIf you want to contribute a new translation, please visit "
                        + "http://maven.apache.org/plugins/localization.html for detailed instructions." );
                }

                continue;
            }

            localesList.add( locale );
        }

        if ( localesList.isEmpty() )
        {
            localesList = Collections.singletonList( DEFAULT_LOCALE );
        }

        return localesList;
    }

    /**
     * Converts a locale code like "en", "en_US" or "en_US_win" to a <code>java.util.Locale</code>
     * object.
     * <p>If localeCode = <code>default</code>, return the current value of the default locale for this instance
     * of the Java Virtual Machine.</p>
     *
     * @param localeCode the locale code string.
     * @return a java.util.Locale object instanced or null if errors occurred
     * @see <a href="http://java.sun.com/j2se/1.4.2/docs/api/java/util/Locale.html">java.util.Locale#getDefault()</a>
     */
    private Locale codeToLocale( String localeCode )
    {
        if ( localeCode == null )
        {
            return null;
        }

        if ( "default".equalsIgnoreCase( localeCode ) )
        {
            return Locale.getDefault();
        }

        String language = "";
        String country = "";
        String variant = "";

        StringTokenizer tokenizer = new StringTokenizer( localeCode, "_" );
        final int maxTokens = 3;
        if ( tokenizer.countTokens() > maxTokens )
        {
            if ( getLogger().isWarnEnabled() )
            {
                getLogger().warn( "Invalid java.util.Locale format for '" + localeCode + "' entry - IGNORING" );
            }
            return null;
        }

        if ( tokenizer.hasMoreTokens() )
        {
            language = tokenizer.nextToken();
            if ( tokenizer.hasMoreTokens() )
            {
                country = tokenizer.nextToken();
                if ( tokenizer.hasMoreTokens() )
                {
                    variant = tokenizer.nextToken();
                }
            }
        }

        return new Locale( language, country, variant );
    }

    // ----------------------------------------------------------------------
    // Protected methods
    // ----------------------------------------------------------------------

    /**
     * @param path could be null.
     * @return the path normalized, i.e. by eliminating "/../" and "/./" in the path.
     * @see FilenameUtils#normalize(String)
     */
    protected static String getNormalizedPath( String path )
    {
        String normalized = FilenameUtils.normalize( path );
        if ( normalized == null )
        {
            normalized = path;
        }
        return ( normalized == null ) ? null : normalized.replace( '\\', '/' );
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * @param project not null
     * @param localRepository not null
     * @param repositories not null
     * @param locale not null
     * @return the resolved site descriptor
     * @throws IOException if any
     * @throws ArtifactResolutionException if any
     * @throws ArtifactNotFoundException if any
     */
    private File resolveSiteDescriptor( MavenProject project, ArtifactRepository localRepository,
                                        List<ArtifactRepository> repositories, Locale locale )
        throws IOException, ArtifactResolutionException, ArtifactNotFoundException
    {
        File result;

        // TODO: this is a bit crude - proper type, or proper handling as metadata rather than an artifact in 2.1?
        Artifact artifact = artifactFactory.createArtifactWithClassifier( project.getGroupId(),
                                                                          project.getArtifactId(),
                                                                          project.getVersion(), "xml",
                                                                          "site_" + locale.getLanguage() );

        boolean found = false;
        try
        {
            artifactResolver.resolve( artifact, repositories, localRepository );

            result = artifact.getFile();

            // we use zero length files to avoid re-resolution (see below)
            if ( result.length() > 0 )
            {
                found = true;
            }
            else
            {
                getLogger().debug( "Skipped site descriptor for locale " + locale.getLanguage() );
            }
        }
        catch ( ArtifactNotFoundException e )
        {
            getLogger().debug( "Unable to locate site descriptor for locale " + locale.getLanguage() + ": " + e );

            // we can afford to write an empty descriptor here as we don't expect it to turn up later in the remote
            // repository, because the parent was already released (and snapshots are updated automatically if changed)
            result = new File( localRepository.getBasedir(), localRepository.pathOf( artifact ) );
            result.getParentFile().mkdirs();
            result.createNewFile();
        }

        if ( !found )
        {
            artifact = artifactFactory.createArtifactWithClassifier( project.getGroupId(), project.getArtifactId(),
                                                                     project.getVersion(), "xml", "site" );
            try
            {
                artifactResolver.resolve( artifact, repositories, localRepository );
            }
            catch ( ArtifactNotFoundException e )
            {
                // see above regarding this zero length file
                result = new File( localRepository.getBasedir(), localRepository.pathOf( artifact ) );
                result.getParentFile().mkdirs();
                result.createNewFile();

                throw e;
            }

            result = artifact.getFile();

            // we use zero length files to avoid re-resolution (see below)
            if ( result.length() == 0 )
            {
                getLogger().debug( "Skipped remote site descriptor check" );
                result = null;
            }
        }

        return result;
    }

    /**
     * @param depth depth of project
     * @param siteDirectory, can be null if project.basedir is null, ie POM from repository
     * @param locale not null
     * @param project not null
     * @param reactorProjects not null
     * @param localRepository not null
     * @param repositories not null
     * @param origProps not null
     * @return the decoration model depending the locale
     * @throws SiteToolException if any
     */
    private DecorationModel getDecorationModel( int depth, File siteDirectory, Locale locale, MavenProject project,
                                                MavenProject parentProject, List<MavenProject> reactorProjects,
                                                ArtifactRepository localRepository,
                                                List<ArtifactRepository> repositories, Map<String, String> origProps )
        throws SiteToolException
    {
        Map<String, String> props = new HashMap<String, String>( origProps );

        File siteDescriptor;
        if ( project.getBasedir() == null )
        {
            // POM is in the repository, look there for site descriptor
            try
            {
                siteDescriptor = getSiteDescriptorFromRepository( project, localRepository, repositories, locale );
            }
            catch ( SiteToolException e )
            {
                throw new SiteToolException( "The site descriptor cannot be resolved from the repository: "
                    + e.getMessage(), e );
            }
        }
        else
        {
            siteDescriptor = getSiteDescriptor( siteDirectory, locale );
        }

        String siteDescriptorContent = null;
        long siteDescriptorLastModified = 0L;
        Reader siteDescriptorReader = null;
        try
        {
            if ( siteDescriptor != null && siteDescriptor.exists() )
            {
                getLogger().debug( "Reading site descriptor from " + siteDescriptor );
                siteDescriptorReader = ReaderFactory.newXmlReader( siteDescriptor );
                siteDescriptorContent = IOUtil.toString( siteDescriptorReader );
                siteDescriptorLastModified = siteDescriptor.lastModified();
            }
            else
            {
                getLogger().debug( "No site descriptor found for " + project.getId() );
            }
        }
        catch ( IOException e )
        {
            throw new SiteToolException( "The site descriptor cannot be read!", e );
        }
        finally
        {
            IOUtil.close( siteDescriptorReader );
        }

        DecorationModel decoration = null;
        if ( siteDescriptorContent != null )
        {
            siteDescriptorContent = getInterpolatedSiteDescriptorContent( props, project, siteDescriptorContent );

            decoration = readDecorationModel( siteDescriptorContent );
            decoration.setLastModified( siteDescriptorLastModified );
        }

        if ( parentProject != null && ( decoration == null || decoration.isMergeParent() ) )
        {
            depth++;
            getLogger().debug( depth + "> Looking for parent project site descriptor: " + parentProject.getId() );

            MavenProject parentParentProject = getParentProject( parentProject, reactorProjects, localRepository );

            File parentSiteDirectory = null;

            if ( parentProject.getBasedir() != null )
            {
                String siteRelativePath = getRelativeFilePath( project.getBasedir().getAbsolutePath(),
                                                               siteDescriptor.getParentFile().getAbsolutePath() );

                parentSiteDirectory = new File( parentProject.getBasedir(), siteRelativePath );
                // notice: using same siteRelativePath for parent as current project; may be wrong if site plugin
                // has different configuration. But this is a rare case (this only has impact if parent if from reactor)
            }

            DecorationModel parent =
                getDecorationModel( depth, parentSiteDirectory, locale, parentProject, parentParentProject,
                                    reactorProjects, localRepository, repositories, props );

            // MSHARED-116 requires an empty decoration model (instead of a null one)
            // MSHARED-145 requires us to do this only if there is a parent to merge it with
            if ( decoration == null && parent != null )
            {
                // we have no site descriptor: merge the parent into an empty one
                decoration = new DecorationModel();
            }

            String name = project.getName();
            if ( decoration != null && StringUtils.isNotEmpty( decoration.getName() ) )
            {
                name = decoration.getName();
            }

            // Merge the parent and child site descriptors
            String projectDistMgmnt = getDistMgmntSiteUrl( project );
            String parentDistMgmnt = getDistMgmntSiteUrl( parentProject );
            if ( getLogger().isDebugEnabled() )
            {
                getLogger().debug( "assembling decoration model inheritance: distributionManagement.site.url child = "
                    + projectDistMgmnt + " and parent = " + parentDistMgmnt );
            }
            assembler.assembleModelInheritance( name, decoration, parent, projectDistMgmnt,
                                                parentDistMgmnt == null ? projectDistMgmnt : parentDistMgmnt );

            getLogger().debug( depth + "< decoration model ready for: " + parentProject.getId() );
        }

        return decoration;
    }

    /**
     * @param siteDescriptorContent not null
     * @return the decoration model object
     * @throws SiteToolException if any
     */
    private DecorationModel readDecorationModel( String siteDescriptorContent )
        throws SiteToolException
    {
        DecorationModel decoration;
        try
        {
            decoration = new DecorationXpp3Reader().read( new StringReader( siteDescriptorContent ) );
        }
        catch ( XmlPullParserException e )
        {
            throw new SiteToolException( "Error parsing site descriptor", e );
        }
        catch ( IOException e )
        {
            throw new SiteToolException( "Error reading site descriptor", e );
        }
        return decoration;
    }

    private static String buildRelativePath( final String toPath,  final String fromPath, final char separatorChar )
    {
        // use tokenizer to traverse paths and for lazy checking
        StringTokenizer toTokeniser = new StringTokenizer( toPath, String.valueOf( separatorChar ) );
        StringTokenizer fromTokeniser = new StringTokenizer( fromPath, String.valueOf( separatorChar ) );

        int count = 0;

        // walk along the to path looking for divergence from the from path
        while ( toTokeniser.hasMoreTokens() && fromTokeniser.hasMoreTokens() )
        {
            if ( separatorChar == '\\' )
            {
                if ( !fromTokeniser.nextToken().equalsIgnoreCase( toTokeniser.nextToken() ) )
                {
                    break;
                }
            }
            else
            {
                if ( !fromTokeniser.nextToken().equals( toTokeniser.nextToken() ) )
                {
                    break;
                }
            }

            count++;
        }

        // reinitialize the tokenizers to count positions to retrieve the
        // gobbled token

        toTokeniser = new StringTokenizer( toPath, String.valueOf( separatorChar ) );
        fromTokeniser = new StringTokenizer( fromPath, String.valueOf( separatorChar ) );

        while ( count-- > 0 )
        {
            fromTokeniser.nextToken();
            toTokeniser.nextToken();
        }

        StringBuilder relativePath = new StringBuilder();

        // add back refs for the rest of from location.
        while ( fromTokeniser.hasMoreTokens() )
        {
            fromTokeniser.nextToken();

            relativePath.append( ".." );

            if ( fromTokeniser.hasMoreTokens() )
            {
                relativePath.append( separatorChar );
            }
        }

        if ( relativePath.length() != 0 && toTokeniser.hasMoreTokens() )
        {
            relativePath.append( separatorChar );
        }

        // add fwd fills for whatever's left of to.
        while ( toTokeniser.hasMoreTokens() )
        {
            relativePath.append( toTokeniser.nextToken() );

            if ( toTokeniser.hasMoreTokens() )
            {
                relativePath.append( separatorChar );
            }
        }
        return relativePath.toString();
    }

    /**
     * @param project not null
     * @param models not null
     * @param menu not null
     */
    private void populateModulesMenuItemsFromModels( MavenProject project, List<Model> models, Menu menu )
    {
        for ( Model model : models )
        {
            String reactorUrl = getDistMgmntSiteUrl( model );
            String name = ( model.getName() == null ) ? model.getArtifactId() : model.getName();

            appendMenuItem( project, menu, name, reactorUrl, model.getArtifactId() );
        }
    }

    /**
     * @param project not null
     * @param menu not null
     * @param name not null
     * @param href could be null
     * @param defaultHref not null
     */
    private void appendMenuItem( MavenProject project, Menu menu, String name, String href, String defaultHref )
    {
        String selectedHref = href;

        if ( selectedHref == null )
        {
            selectedHref = defaultHref;
        }

        MenuItem item = new MenuItem();
        item.setName( name );

        String baseUrl = getDistMgmntSiteUrl( project );
        if ( baseUrl != null )
        {
            selectedHref = getRelativePath( selectedHref, baseUrl );
        }

        if ( selectedHref.endsWith( "/" ) )
        {
            item.setHref( selectedHref + "index.html" );
        }
        else
        {
            item.setHref( selectedHref + "/index.html" );
        }
        menu.addItem( item );
    }

    /**
     * @param name not null
     * @param href not null
     * @param categoryReports not null
     * @param locale not null
     * @return the menu item object
     */
    private MenuItem createCategoryMenu( String name, String href, List<MavenReport> categoryReports, Locale locale )
    {
        MenuItem item = new MenuItem();
        item.setName( name );
        item.setCollapse( true );
        item.setHref( href );

        // MSHARED-172, allow reports to define their order in some other way?
        //Collections.sort( categoryReports, new ReportComparator( locale ) );

        for ( MavenReport report : categoryReports )
        {
            MenuItem subitem = new MenuItem();
            subitem.setName( report.getName( locale ) );
            subitem.setHref( report.getOutputName() + ".html" );
            item.getItems().add( subitem );
        }

        return item;
    }

    // ----------------------------------------------------------------------
    // static methods
    // ----------------------------------------------------------------------

    /**
     * Convenience method.
     *
     * @param list could be null
     * @return true if the list is <code>null</code> or empty
     */
    private static boolean isEmptyList( List<?> list )
    {
        return list == null || list.isEmpty();
    }

    /**
     * Return distributionManagement.site.url if defined, null otherwise.
     *
     * @param project not null
     * @return could be null
     */
    private static String getDistMgmntSiteUrl( MavenProject project )
    {
        return getDistMgmntSiteUrl( project.getDistributionManagement() );
    }

    /**
     * Return distributionManagement.site.url if defined, null otherwise.
     *
     * @param model not null
     * @return could be null
     */
    private static String getDistMgmntSiteUrl( Model model )
    {
        return getDistMgmntSiteUrl( model.getDistributionManagement() );
    }

    private static String getDistMgmntSiteUrl( DistributionManagement distMgmnt )
    {
        if ( distMgmnt != null && distMgmnt.getSite() != null && distMgmnt.getSite().getUrl() != null )
        {
            return urlEncode( distMgmnt.getSite().getUrl() );
        }

        return null;
    }

    private static String urlEncode( final String url )
    {
        if ( url == null )
        {
            return null;
        }

        try
        {
            return new File( url ).toURI().toURL().toExternalForm();
        }
        catch ( MalformedURLException ex )
        {
            return url; // this will then throw somewhere else
        }
    }

    private static void setDistMgmntSiteUrl( Model model, String url )
    {
        if ( model.getDistributionManagement() == null )
        {
            model.setDistributionManagement( new DistributionManagement() );
        }

        if ( model.getDistributionManagement().getSite() == null )
        {
            model.getDistributionManagement().setSite( new Site() );
        }

        model.getDistributionManagement().getSite().setUrl( url );
    }

    private void checkNotNull( String name, Object value )
    {
        if ( value == null )
        {
            throw new IllegalArgumentException( "The parameter '" + name + "' cannot be null." );
        }
    }
}
