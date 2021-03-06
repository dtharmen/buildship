/*
 * Copyright (c) 2016 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Etienne Studer & Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 */

package org.eclipse.buildship.core.configuration.internal;

import java.io.File;
import java.lang.reflect.Type;
import java.util.Map;

import org.osgi.service.prefs.BackingStoreException;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import org.eclipse.buildship.core.GradlePluginsRuntimeException;
import org.eclipse.buildship.core.configuration.ProjectConfiguration;

/**
 * Persistence delegate which cleans up the legacy, json-based project configuration format.
 */
final class LegacyCleaningProjectConfigurationPersistence implements ProjectConfigurationPersistence {

    private static final String LEGACY_GRADLE_PREFERENCES_LOCATION = ".settings/gradle.prefs";
    private static final String LEGACY_GRADLE_PREFERENCES_FILE_NAME_WITHOUT_EXTENSION = "gradle";

    private final ProjectConfigurationPersistence delegate;

    LegacyCleaningProjectConfigurationPersistence(ProjectConfigurationPersistence delegate) {
        this.delegate = Preconditions.checkNotNull(delegate);
    }

    @Override
    public void saveProjectConfiguration(ProjectConfiguration configuration, IProject project) {
        cleanupLegacyConfiguration(project);
        this.delegate.saveProjectConfiguration(configuration, project);
    }

    @Override
    public void deleteProjectConfiguration(IProject project) {
        this.delegate.deleteProjectConfiguration(project);
    }

    @Override
    public ProjectConfiguration readProjectConfiguration(IProject project) {
        if (hasLegacyConfiguration(project)) {
            return readLegacyProjectConfiguration(project);
        } else {
            return this.delegate.readProjectConfiguration(project);
        }
    }

    private static boolean hasLegacyConfiguration(IProject project) {
        return getLegacyConfigurationFile(project).exists();
    }

    private static File getLegacyConfigurationFile(IProject project) {
        return new File(project.getLocation().toFile(), LEGACY_GRADLE_PREFERENCES_LOCATION);
    }

    private static ProjectConfiguration readLegacyProjectConfiguration(IProject workspaceProject) {
        return readLegacyConfiguration(workspaceProject).toProjectConfiguration(workspaceProject);
    }

    private static ProjectConfigurationProperties readLegacyConfiguration(IProject project) {
        Map<String, Map<String, String>> parsedJson = parseLegacyConfigurationFile(project);
        Map<String, String> config = parsedJson.get("1.0");

        String projectPath = config.get("project_path");
        String projectDir = config.get("connection_project_dir");
        String gradleDistribution = config.get("connection_gradle_distribution");
        return ProjectConfigurationProperties.from(projectPath, projectDir, gradleDistribution);
    }

    private static Map<String, Map<String, String>> parseLegacyConfigurationFile(IProject project) {
        File gradlePrefsFile = getLegacyConfigurationFile(project);
        String malformedFileMessage = String.format("Project %s contains a malformed gradle.prefs file. Please re-run the import wizard.", project.getName());

        Map<String, Map<String, String>> parsedJson = null;
        try {
            String json = Files.toString(gradlePrefsFile, Charsets.UTF_8);
            Gson gson = new GsonBuilder().create();
            parsedJson = gson.fromJson(json, createMapTypeToken());
        } catch (Exception e) {
            throw new GradlePluginsRuntimeException(malformedFileMessage, e);
        }
        if (parsedJson == null) {
            throw new GradlePluginsRuntimeException(malformedFileMessage);
        }
        return parsedJson;
    }

    private static Type createMapTypeToken() {
        return new TypeToken<Map<String, Map<String, String>>>() {
        }.getType();
    }

    private static void cleanupLegacyConfiguration(IProject project) {
        Preconditions.checkNotNull(project);
        Preconditions.checkArgument(project.isAccessible());

        if (hasLegacyConfiguration(project)) {
            //remove preferences by descending order of abstraction layer
            removeLegacyPreferencesFromPreferenceStore(project);
            deleteLegacyPreferencesFromEclipseFileSystem(project);
            deleteLegacyPreferenceFileFromJavaIoFileSystem(project);
        }
    }

    private static void removeLegacyPreferencesFromPreferenceStore(IProject project) {
        try {
            ProjectScope projectScope = new ProjectScope(project);
            IEclipsePreferences node = projectScope.getNode(LEGACY_GRADLE_PREFERENCES_FILE_NAME_WITHOUT_EXTENSION);
            if (node != null) {
                node.removeNode();
            }
        } catch (BackingStoreException e) {
            throw new GradlePluginsRuntimeException(getCouldNotDeleteMessage(project), e);
        }
    }

    private static void deleteLegacyPreferencesFromEclipseFileSystem(IProject project) {
        try {
            IFile legacyConfigurationFile = project.getFile(LEGACY_GRADLE_PREFERENCES_LOCATION);
            if (legacyConfigurationFile.exists()) {
                legacyConfigurationFile.delete(true, null);
            }
        } catch (CoreException e) {
            throw new GradlePluginsRuntimeException(getCouldNotDeleteMessage(project), e);
        }
    }

    private static void deleteLegacyPreferenceFileFromJavaIoFileSystem(IProject project) {
        File legacyConfigurationFile = getLegacyConfigurationFile(project);
        if (legacyConfigurationFile.exists()) {
            boolean deleted = legacyConfigurationFile.delete();
            if (!deleted) {
                throw new GradlePluginsRuntimeException(getCouldNotDeleteMessage(project));
            }
        }
    }

    private static String getCouldNotDeleteMessage(IProject project) {
        return String.format("Cannot clean up legacy project configuration for project %s.", project.getName());
    }

}
