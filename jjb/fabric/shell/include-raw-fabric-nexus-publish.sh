#!/bin/bash -e
#
# SPDX-License-Identifier: Apache-2.0
##############################################################################
# Copyright (c) 2018 IBM Corporation, The Linux Foundation and others.
#
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License 2.0
# which accompanies this distribution, and is available at
# https://www.apache.org/licenses/LICENSE-2.0
##############################################################################

# This script publishes the docker images to Nexus3 and binaries to Nexus2 if
# the nightly build is successful.

cd $WORKSPACE/gopath/src/github.com/hyperledger/fabric || exit 1
ORG_NAME=hyperledger/fabric
NEXUS_URL=nexus3.hyperledger.org:10003
TAG=$GIT_COMMIT &&  COMMIT_TAG=${TAG:0:7}
ARCH=$(go env GOARCH) && echo "--------->" $ARCH
PROJECT_VERSION=$PUSH_VERSION
echo "-----------> PROJECT_VERSION:" $PROJECT_VERSION
STABLE_TAG=$ARCH-$PROJECT_VERSION
echo "-----------> STABLE_TAG:" $STABLE_TAG

cd ../fabric-ca || exit
CA_COMMIT=$(git log -1 --pretty=format:"%h")
echo "CA COMMIT" $CA_COMMIT
cd - || exit

fabric_DockerTag() {
    for IMAGES in ${IMAGES_LIST[*]}; do
         echo "----------> $IMAGES"
         echo
         docker tag $ORG_NAME-$IMAGES $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG
	 if [[ "$GERRIT_BRANCH" = "master" ]]; then
            echo "-----> tag latest"
            docker tag $ORG_NAME-$IMAGES $NEXUS_URL/$ORG_NAME-$IMAGES:$ARCH-latest
         fi
    done
         docker images
         echo "----------> $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG"
}

fabric_Ca_DockerTag() {
    for IMAGES in ca $1; do
         echo "----------> $IMAGES"
         echo
         docker tag $ORG_NAME-$IMAGES $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG
         echo "-----> tag latest"
	 if [[ "$GERRIT_BRANCH" = "master" ]]; then
            docker tag $ORG_NAME-$IMAGES $NEXUS_URL/$ORG_NAME-$IMAGES:$ARCH-latest
         fi
    done
         docker images
         echo "----------> $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG"
}

dockerFabricPush() {
    for IMAGES in ${IMAGES_LIST[*]}; do
         echo "-----------> $IMAGES"
         docker push $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG
	 if [[ "$GERRIT_BRANCH" = "master" ]]; then
            echo "-----> push latest"
            docker push $NEXUS_URL/$ORG_NAME-$IMAGES:$ARCH-latest
         fi
         echo
    done
         docker images
         echo "-----------> $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG"
}

dockerFabricCaPush() {
    for IMAGES in ca $1; do
         echo "-----------> $IMAGES"
         docker push $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG
	 if [[ "$GERRIT_BRANCH" = "master" ]]; then
            echo "-----> push latest"
            docker push $NEXUS_URL/$ORG_NAME-$IMAGES:$ARCH-latest
         fi
         echo
    done
         docker images
         echo "-----------> $NEXUS_URL/$ORG_NAME-$IMAGES:$STABLE_TAG"
}

if [[ "$GERRIT_BRANCH" = "master" ]]; then
   IMAGES_LIST=(baseos peer orderer ccenv tools)
   fabric_DockerTag  #Tag Fabric Docker Images
   dockerFabricPush  #Push Fabric Docker Images to Nexus3
else
   IMAGES_LIST=(peer orderer ccenv tools)
   fabric_DockerTag  #Tag Fabric Docker Images
   dockerFabricPush  #Push Fabric Docker Images to Nexus3
fi

# Tag Fabric Ca Docker Images
if [ $ARCH = s390x ] || [ $ARCH = ppc64le ]; then
    fabric_Ca_DockerTag
else
    fabric_Ca_DockerTag ca-fvt
fi

# Push Fabric Ca Docker Images to Nexus3
if [ $ARCH = s390x ] || [ $ARCH = ppc64le ]; then
    dockerFabricCaPush
else
    dockerFabricCaPush ca-fvt
fi
# Listout all docker images Before and After Push to NEXUS
docker images | grep "nexus*"

echo "------> Current space information."
df -h

# Publish fabric binaries
# Don't publish same binaries if they are available in nexus
if [ $GERRIT_BRANCH = "master" ]; then
    PROJECT_VERSION=latest
else
    PROJECT_VERSION=$PUSH_VERSION
fi

curl -L https://nexus.hyperledger.org/content/repositories/snapshots/org/hyperledger/fabric/hyperledger-fabric-$PROJECT_VERSION > output.xml

if cat output.xml | grep $COMMIT_TAG > /dev/null; then
    echo "--------> INFO: $COMMIT_TAG is already available... SKIP BUILD"
elif [[ $ARCH == "amd64" ]]; then
        # Push fabric-binaries to nexus2
        for binary in linux-amd64 windows-amd64 darwin-amd64 linux-s390x; do
            cd $WORKSPACE/gopath/src/github.com/hyperledger/fabric/release/$binary || exit
	    tar -czf hyperledger-fabric-$binary.$PROJECT_VERSION.$COMMIT_TAG.tar.gz *
            echo "----------> Pushing hyperledger-fabric-$binary.$PROJECT_VERSION.tar.gz to maven.."
            mvn -B org.apache.maven.plugins:maven-deploy-plugin:deploy-file \
            -DupdateReleaseInfo=true \
            -Dfile=$WORKSPACE/gopath/src/github.com/hyperledger/fabric/release/$binary/hyperledger-fabric-$binary.$PROJECT_VERSION.$COMMIT_TAG.tar.gz \
            -DrepositoryId=hyperledger-snapshots \
            -Durl=https://nexus.hyperledger.org/content/repositories/snapshots/ \
            -DgroupId=org.hyperledger.fabric \
            -Dversion=$binary.$PROJECT_VERSION-SNAPSHOT \
            -DartifactId=hyperledger-fabric-$PROJECT_VERSION \
            -DuniqueVersion=false \
            -Dpackaging=tar.gz \
            -gs $GLOBAL_SETTINGS_FILE -s $SETTINGS_FILE
            echo "-------> DONE <----------"
            rm -f hyperledger-fabric-$binary.$PROJECT_VERSION.$COMMIT_TAG.tar.gz || true
        done
    else
       echo "-------> Dont publish binaries from s390x or ppc64le platform"
fi

# Disable publishing fabric-ca binaries from nightly builds till we identify a way
# to publish both fabric and fabric-ca binaries from same job
# Once it is available, just uncomment this below

: '
# fabric-ca binaries

if [ $ARCH = "amd64" ]; then
       # Push fabric-binaries to nexus2
       for binary in linux-amd64 windows-amd64 darwin-amd64 linux-ppc64le linux-s390x; do
              cd $WORKSPACE/gopath/src/github.com/hyperledger/fabric-ca/release/$binary && tar -czf hyperledger-fabric-ca-$binary.$PROJECT_VERSION.$CA_COMMIT.tar.gz *
              echo "----------> Pushing hyperledger-fabric-ca-$binary.$PROJECT_VERSION.$CA_COMMIT.tar.gz to maven.."
              mvn -B org.apache.maven.plugins:maven-deploy-plugin:deploy-file \
              -DupdateReleaseInfo=true \
              -Dfile=$WORKSPACE/gopath/src/github.com/hyperledger/fabric-ca/release/$binary/hyperledger-fabric-ca-$binary.$PROJECT_VERSION.$CA_COMMIT.tar.gz \
              -DrepositoryId=hyperledger-releases \
              -Durl=https://nexus.hyperledger.org/content/repositories/releases/ \
              -DgroupId=org.hyperledger.fabric-ca \
              -Dversion=$binary.$PROJECT_VERSION-$CA_COMMIT \
              -DartifactId=hyperledger-fabric-ca-$PROJECT_VERSION \
              -DgeneratePom=true \
              -DuniqueVersion=false \
              -Dpackaging=tar.gz \
              -gs $GLOBAL_SETTINGS_FILE -s $SETTINGS_FILE
              echo "-------> DONE <----------"
              rm -f hyperledger-fabric-$binary.$PROJECT_VERSION.$COMMIT_TAG.tar.gz || true
       done
else
       echo "-------> Dont publish binaries from s390x platform"
fi
'
