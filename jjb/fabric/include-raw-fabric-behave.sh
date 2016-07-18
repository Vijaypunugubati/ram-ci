#!/bin/bash -eu
set -o pipefail

cd gopath/src/github.com/hyperledger/fabric

# Configure ip/port
ip="$(ifconfig docker0 | grep 'inet[^6]' | awk '{print $2}' | cut -d ':' -f 2)"
port=2375
# script
echo "Executing Behave test scripts"
sed -i -e 's/172.17.0.1:2375\b/'"$ip:$port"'/g' bddtests/compose-defaults.yml
make behave BEHAVE_OPTS="-D logs=Y -o testsummary.log --junit --format=pretty"
