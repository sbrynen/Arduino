/*
 * This file is part of Arduino.
 *
 * Copyright 2014 Arduino LLC (http://www.arduino.cc/)
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * As a special exception, you may use this file as part of a free software
 * library without restriction.  Specifically, if other files instantiate
 * templates or use macros or inline functions from this file, or you compile
 * this file and link it with other files to produce an executable, this
 * file does not by itself cause the resulting executable to be covered by
 * the GNU General Public License.  This exception does not however
 * invalidate any other reasons why the executable file might be covered by
 * the GNU General Public License.
 */
package cc.arduino.contributions.packages;

import cc.arduino.contributions.DownloadableContributionBuiltInAtTheBottomComparator;
import cc.arduino.contributions.filters.BuiltInPredicate;
import cc.arduino.contributions.filters.InstalledPredicate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.mrbean.MrBeanModule;
import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimaps;
import processing.app.debug.TargetPackage;
import processing.app.debug.TargetPlatform;
import processing.app.debug.TargetPlatformException;
import processing.app.helpers.PreferencesMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static processing.app.helpers.filefilters.OnlyDirs.ONLY_DIRS;

public class ContributionsIndexer {

  private final File packagesFolder;
  private final File stagingFolder;
  private final File indexFile;
  private ContributionsIndex index;

  public ContributionsIndexer(File preferencesFolder) {
    packagesFolder = new File(preferencesFolder, "packages");
    stagingFolder = new File(preferencesFolder, "staging" + File.separator +
            "packages");
    indexFile = new File(preferencesFolder, "package_index.json");
  }

  // public static void main(String args[]) throws Exception {
  // File indexFile = new File(args[0]);
  //
  // // VerifyResult verify = ClearSignedVerifier.verify(indexFile,
  // // new PackagersPublicKeys());
  // // if (!verify.verified)
  // // throw new Exception("Invalid index file!");
  //
  // ContributionsIndexer indexer = new ContributionsIndexer(null);
  // // indexer.parse(new ByteArrayInputStream(verify.clearText));
  // indexer.parseIndex(indexFile);
  // indexer.syncWithFilesystem();
  // }

  public void parseIndex() throws IOException {
    // Parse index file
    parseIndex(indexFile);

    List<ContributedPackage> packages = index.getPackages();
    for (ContributedPackage pack : packages) {
      for (ContributedPlatform platform : pack.getPlatforms()) {
        // Set a reference to parent packages
        platform.setParentPackage(pack);

        // Resolve tools dependencies (works also as a check for file integrity)
        platform.resolveToolsDependencies(packages);
      }
    }

    index.fillCategories();
  }

  private void parseIndex(File indexFile) throws IOException {
    InputStream indexIn = new FileInputStream(indexFile);
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new MrBeanModule());
    mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    mapper.configure(DeserializationFeature.EAGER_DESERIALIZER_FETCH, true);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    index = mapper.readValue(indexIn, ContributionsIndex.class);
  }

  public void syncWithFilesystem(File hardwareFolder) throws IOException {
    syncBuiltInHardwareFolder(hardwareFolder);

    syncLocalPackagesFolder();
  }

  public void syncBuiltInHardwareFolder(File hardwareFolder) throws IOException {
    for (File folder : hardwareFolder.listFiles(ONLY_DIRS)) {
      ContributedPackage pack = index.findPackage(folder.getName());
      if (pack != null) {
        syncBuiltInPackageWithFilesystem(pack, folder);

        File toolsFolder = new File(hardwareFolder, "tools");
        if (toolsFolder.isDirectory()) {
          for (File toolFolder : toolsFolder.listFiles(ONLY_DIRS)) {
            File builtInToolsMetadata = new File(toolFolder, "builtin_tools_versions.txt");
            if (builtInToolsMetadata.isFile()) {
              PreferencesMap toolsMetadata = new PreferencesMap(builtInToolsMetadata).subTree(pack.getName());
              for (Map.Entry<String, String> toolMetadata : toolsMetadata.entrySet()) {
                syncToolWithFilesystem(pack, toolFolder, toolMetadata.getKey(), toolMetadata.getValue());
              }
            }
          }
        }
      }
    }
  }

  private void syncBuiltInPackageWithFilesystem(ContributedPackage pack, File hardwareFolder) throws IOException {
    // Scan all hardware folders and mark as installed all the tools found.
    for (File platformFolder : hardwareFolder.listFiles(ONLY_DIRS)) {
      File platformTxt = new File(platformFolder, "platform.txt");
      String version = new PreferencesMap(platformTxt).get("version");
      ContributedPlatform platform = syncHardwareWithFilesystem(pack, platformFolder, platformFolder.getName(), version);
      if (platform != null) {
        platform.setReadOnly(true);
      }
    }
  }

  public void syncLocalPackagesFolder() {
    if (!packagesFolder.isDirectory())
      return;

    // Scan all hardware folders and mark as installed all the
    // platforms found.
    for (File folder : packagesFolder.listFiles(ONLY_DIRS)) {
      ContributedPackage pack = index.findPackage(folder.getName());
      if (pack != null) {
        syncPackageWithFilesystem(pack, folder);
      }
    }
  }

  private void syncPackageWithFilesystem(ContributedPackage pack, File root) {
    // Scan all hardware folders and mark as installed all the tools found.
    File hardwareFolder = new File(root, "hardware");
    if (hardwareFolder.isDirectory()) {
      for (File platformFolder : hardwareFolder.listFiles(ONLY_DIRS)) {
        for (File versionFolder : platformFolder.listFiles(ONLY_DIRS)) {
          syncHardwareWithFilesystem(pack, versionFolder, platformFolder.getName(), versionFolder.getName());
        }
      }
    }

    // Scan all tools folders and mark as installed all the tools found.
    File toolsFolder = new File(root, "tools");
    if (toolsFolder.isDirectory()) {
      for (File toolFolder : toolsFolder.listFiles(ONLY_DIRS)) {
        for (File versionFolder : toolFolder.listFiles(ONLY_DIRS)) {
          syncToolWithFilesystem(pack, versionFolder, toolFolder.getName(), versionFolder.getName());
        }
      }
    }
  }

  private void syncToolWithFilesystem(ContributedPackage pack, File installationFolder, String toolName, String version) {
    ContributedTool tool = pack.findTool(toolName, version);
    if (tool == null) {
      return;
    }
    DownloadableContribution contrib = tool.getDownloadableContribution();
    if (contrib == null) {
      System.err.println(tool + " seems to have no downloadable contributions for your operating system, but it is installed in\n" + installationFolder);
      return;
    }
    contrib.setInstalled(true);
    contrib.setInstalledFolder(installationFolder);
  }

  private ContributedPlatform syncHardwareWithFilesystem(ContributedPackage pack, File installationFolder, String architecture, String version) {
    ContributedPlatform platform = pack.findPlatform(architecture, version);
    if (platform != null) {
      platform.setInstalled(true);
      platform.setReadOnly(false);
      platform.setInstalledFolder(installationFolder);
    }
    return platform;
  }

  @Override
  public String toString() {
    return index.toString();
  }

  public List<TargetPackage> createTargetPackages() throws TargetPlatformException {
    List<TargetPackage> packages = new ArrayList<TargetPackage>();

    for (ContributedPackage aPackage : index.getPackages()) {
      ContributedTargetPackage targetPackage = new ContributedTargetPackage(aPackage.getName());

      List<ContributedPlatform> platforms = new LinkedList<ContributedPlatform>(Collections2.filter(aPackage.getPlatforms(), new InstalledPredicate()));
      Collections.sort(platforms, new DownloadableContributionBuiltInAtTheBottomComparator());

      for (ContributedPlatform platform : platforms) {
        String arch = platform.getArchitecture();
        File folder = platform.getInstalledFolder();

        TargetPlatform targetPlatform = new ContributedTargetPlatform(arch, folder, targetPackage, index);
        if (!targetPackage.hasPlatform(targetPlatform)) {
          targetPackage.addPlatform(targetPlatform);
        }
      }

      if (targetPackage.hasPlatforms()) {
        packages.add(targetPackage);
      }
    }
    return packages;
  }

  /**
   * Check if a ContributedTool is currently in use by an installed platform
   *
   * @param tool
   * @return
   */
  public boolean isContributedToolUsed(ContributedTool tool) {
    for (ContributedPackage pack : index.getPackages()) {
      for (ContributedPlatform platform : pack.getPlatforms()) {
        if (!platform.isInstalled())
          continue;
        for (ContributedTool requiredTool : platform.getResolvedTools()) {
          if (requiredTool.equals(tool))
            return true;
        }
      }
    }
    return false;
  }

  public Set<ContributedTool> getInstalledTools() {
    Set<ContributedTool> tools = new HashSet<ContributedTool>();
    for (ContributedPackage pack : index.getPackages()) {
      Collection<ContributedPlatform> platforms = Collections2.filter(pack.getPlatforms(), new InstalledPredicate());
      ImmutableListMultimap<String, ContributedPlatform> platformsByName = Multimaps.index(platforms, new Function<ContributedPlatform, String>() {
        @Override
        public String apply(ContributedPlatform contributedPlatform) {
          return contributedPlatform.getName();
        }
      });

      for (Map.Entry<String, Collection<ContributedPlatform>> entry : platformsByName.asMap().entrySet()) {
        Collection<ContributedPlatform> platformsWithName = entry.getValue();
        if (platformsWithName.size() > 1) {
          platformsWithName = Collections2.filter(platformsWithName, Predicates.not(new BuiltInPredicate()));
        }
        for (ContributedPlatform platform : platformsWithName) {
          tools.addAll(platform.getResolvedTools());
        }
      }
    }
    return tools;
  }

  public ContributionsIndex getIndex() {
    return index;
  }

  public File getPackagesFolder() {
    return packagesFolder;
  }

  public File getStagingFolder() {
    return stagingFolder;
  }

  public File getIndexFile() {
    return indexFile;
  }

}
