#!/bin/bash
#
# Builds the parts of DomUI that pdp11-web needs and installs them into the local Maven
# repository, so that the reactor build can resolve to.etc.domui:*:1.2-SNAPSHOT. DomUI is not
# on Maven Central. It runs from the repository root so that whatever .mvn/maven.config says
# about the local repository applies to this build and to the reactor's alike.
#
# DomUI is a git submodule (domui/, branch domui-fixed) and deliberately not a module of this
# reactor: its parent pom brings its own plugin setup, Kotlin, Hibernate integrations and a demo
# application, none of which this build should run. -pl ... -am builds just the framework,
# the FontAwesome icon set it refuses to start without, and what those depend on.
#
# Run once after cloning (git clone --recurse-submodules), and again after updating the
# submodule.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$root/domui/pom.xml" ]; then
	echo "domui/ is empty: run 'git submodule update --init' first" >&2
	exit 1
fi

cd "$root"
./mvnw -f "$root/domui/pom.xml" -B --no-transfer-progress install \
	-DskipTests -Dmaven.javadoc.skip=true \
	-pl to.etc.domui,integrations/fontawesome6free -am \
	"$@"
