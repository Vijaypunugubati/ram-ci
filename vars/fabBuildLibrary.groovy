#!/usr/bin/env groovy

properties = null

// Load the properties file from the application repository
def loadProperties() {
  Properties prop = new Properties()
  // BASE_DIR points to gopath/src/github.com/$PROJECT
  // GIT_BASE points to git://cloud.hyperledger.org/mirror/$PROJECT.git
  String propertiesFile = readFile("${WORKSPACE}/${BASE_DIR}/ci.properties")
  prop.load(new StringReader(propertiesFile))
  return prop
}

// Cleanup environment before run the tests
def cleanupEnv() {
  try {
    echo "-------> Clean Environment"
    sh 'figlet CLEAN WS'
    dir("${WORKSPACE}/gopath/src/github.com/hyperledger/ci-management") {
    sh '''set +x -eu
      if [ -d "ci-management" ]; then
        rm -rf ci-management
      fi
      git clone --single-branch -b master --depth=1 git://cloud.hyperledger.org/mirror/ci-management
      cd ci-management
      chmod +x jjb/common-scripts/include-raw-fabric-clean-environment.sh
      ./jjb/common-scripts/include-raw-fabric-clean-environment.sh
    '''
    }
  }
  catch (err) {
    failure_stage = "cleanupEnv"
    currentBuild.result = 'FAILURE'
    throw err
  }
}

// Output all the information about the environment
def envOutput() {
  try {
    echo "-------> Jenkins Environment Details....."
    sh 'figlet ENV OUTPUT'
    sh '''set +x -eu
      uname -a
      cat /etc/*-release
      env
      gcc --version
      docker version
      docker info
      docker-compose version
      pgrep -a docker
      docker images
      docker ps -a
    '''
  }
  catch (err) {
    failure_stage = "envOutput"
    currentBuild.result = 'FAILURE'
    throw err
  }
}

def cloneRepo(project) {
  try {
  def ROOTDIR = pwd()
  if (env.JOB_TYPE != "merge")  {
    // Clone patchset changes on verify Job
    println "$GERRIT_REFSPEC"
    println "$GERRIT_BRANCH"
    checkout([
      $class: 'GitSCM',
        branches: [[name: '$GERRIT_REFSPEC']],
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '$BASE_DIR'],
                    [$class: 'CheckoutOption', timeout: 10]],
        userRemoteConfigs: [[credentialsId: 'hyperledger-jobbuilder', name: 'origin',
                            refspec: '$GERRIT_REFSPEC:$GERRIT_REFSPEC', url: '$GIT_BASE']]])
  } else {
    // Clone latest merged commit on Merge Job
    println "Clone $project repository"
    checkout([
      $class: 'GitSCM',
        branches: [[name: 'refs/heads/$GERRIT_BRANCH']],
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '$BASE_DIR']],
        userRemoteConfigs: [[credentialsId: 'hyperledger-jobbuilder', name: 'origin',
                            refspec: '+refs/heads/$GERRIT_BRANCH:refs/remotes/origin/$GERRIT_BRANCH',
                            url: '$GIT_BASE']]])
    }
    dir("$ROOTDIR/$BASE_DIR") {
    sh '''set +x -eu
      echo " #### COMMIT LOG #### "
      echo
      echo " ####################### "
      git log -n2 --pretty=oneline --abbrev-commit
      echo " ####################### "
    '''
    }
  }
  catch (err) {
    failure_stage = "cloneRepo"
    currentBuild.result = 'FAILURE'
    throw err
  }
}

// Pull Docker images from nexus3
def pullDockerImages(fabBaseVersion, fabImages) {
  wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
  try {
    sh """set +x -eu
      echo "FABRIC_IAMGES: $fabImages"
      echo "BASE_VERSION: $fabBaseVersion"
      echo "MARCH: $MARCH"
      figet -l P U L L I M A G E S
      for fabImages in $fabImages; do
        if [ "\$fabImages" = "javaenv" ]; then
          case $MARCH in
            s390x|ppc64le)
              # Do not pull javaenv if ARCH is s390x
              echo "\033[32m ##### Javaenv image not available on $MARCH ##### \033[0m"
              break;
              ;;
            *)
              set -x
              if ! docker pull $NEXUS_REPO_URL/$ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion-stable > /dev/null 2>&1; then
                echo -e "\033[31m ##### FAILED to pull \$fabImages ##### \033[0m"
              fi
              set +x
              ;;
          esac
        else
          echo "#################################"
          echo "#### Pull \$fabImages Image ####"
          echo "#################################"
          set -x
          if ! docker pull $NEXUS_REPO_URL/$ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion-stable > /dev/null 2>&1; then
            echo -e "\033[31m ##### FAILED to pull \$fabImages ##### \033[0m"
          fi
          set +x
        fi
        echo " ####### TAG \$fabImages ####### "
        set -x
        docker tag $NEXUS_REPO_URL/$ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion-stable $ORG_NAME-"\$fabImages"
        docker tag $NEXUS_REPO_URL/$ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion-stable $ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion
        docker tag $NEXUS_REPO_URL/$ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion-stable $ORG_NAME-"\$fabImages":$fabBaseVersion
        docker rmi -f $NEXUS_REPO_URL/$ORG_NAME-"\$fabImages":$MARCH-$fabBaseVersion-stable
        set +x
      done
      echo
    """
  } catch (err) {
      failure_stage = "pullDockerImages"
      currentBuild.result = 'FAILURE'
      throw err
    }
  }
}

// Pull Thirdparty Docker images from Hyperledger Dockerhub
def pullThirdPartyImages(baseImageVersion, fabThirdPartyImages) {
  wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
  try {
    sh """set +x -eu
      echo "THIRDPARTY_IMAGES: $fabThirdPartyImages"
      echo "BASEIMAGE_VERSION: $baseImageVersion"

      figet -l P U L L I M A G E S

      for baseImage in $fabThirdPartyImages; do
        set -x
        if ! docker pull $ORG_NAME-\$baseImage:$baseImageVersion > /dev/null 2>&1; then
          docker tag $ORG_NAME-\$baseImage:$baseImageVersion $ORG_NAME-\$baseImage
        fi
        set +x
      done
      echo
      docker images | grep hyperledger/fabric
    """
  } catch (err) {
      failure_stage = "pullThirdPartyImages"
      currentBuild.result = 'FAILURE'
      throw err
    }
  }
}
// Pull Binaries into $PROJECT dir
def pullBinaries(fabBaseVersion, fabRepo) {
  wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
  try {
    sh """set +x -eu
      echo "FABRIC_REPO: $fabRepo"
      echo "BASE_VERSION: $fabBaseVersion"

      figlet -c P U L L B I N A R I E S

      for fabRepo in $fabRepo; do
        echo "#################################"
        echo "#### Pull \$fabRepo Binaries ####"
        echo "#################################"
        nexusBinUrl=https://nexus.hyperledger.org/content/repositories/snapshots/org/hyperledger/\$fabRepo/hyperledger-\$fabRepo-$fabBaseVersion/$ARCH-$MARCH.$fabBaseVersion-SNAPSHOT
        echo "NEXUS_BIN_URL: \$nexusBinUrl"
        # Download the maven-metadata.xml file
        curl \$nexusBinUrl/maven-metadata.xml > maven-metadata.xml
        if grep -q "not found in local storage of repository" "maven-metadata.xml"; then
          echo  "FAILED: Unable to download from \$nexusBinUrl"
        else
          # Set latest tar file to the ver
          ver=\$(grep value maven-metadata.xml | sort -u | cut -d "<" -f2|cut -d ">" -f2)
          echo "Version: \$ver"
          # Download tar.gz file and extract it
          curl -L \$nexusBinUrl/hyperledger-\$fabRepo-$fabBaseVersion-\$ver.tar.gz | tar xz
          rm hyperledger-\$fabRepo-*.tar.gz
          rm -f maven-metadata.xml
          echo "Finished pulling \$fabRepo"
          echo
        fi
      done
      # List binaries
      echo " ##### BINARIES ##### "
      ls $WORKSPACE/$BASE_DIR/bin
      echo " #################### "
    """
  } catch (err) {
      failure_stage = "pullBinaries"
      currentBuild.result = 'FAILURE'
      throw err
    }
  }
}

// Clone the repository with specific branch name with depth 1(latest commit)
// 
def cloneScm(repoName, branchName) {
      sh 'cd $WORKSPACE/gopath/src/github.com/hyperledger'
      sh "figlet CLONE $repoName"
      sh "if(git clone --single-branch $branchName --depth=1 git://cloud.hyperledger.org/mirror/$repoName.git)"
    sh """set +x -eu
      cd $repoName
      workDir=\$(pwd | grep -o '[^/]*\$')
      if [ "\$workDir" = "$repoName" ]; then
        echo " #### COMMIT LOG #### "
        echo
        echo " ####################### "
        git log -n2 --pretty=oneline --abbrev-commit
        echo " ####################### "
      else
        echo "======= FAILED to CLONE the repository ======= "
      fi
    """
}
// Build fabric* images
def fabBuildImages(repoName, makeTarget) {
  sh """ set +x -ue
    cd $WORKSPACE/gopath/src/github.com/hyperledger/$repoName
    # Build fab docker images
    figlet BUILD IMAGES
    make clean $makeTarget
  """
}
