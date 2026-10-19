#!/bin/sh
# From repository root. Synthetic tests only; never execute the live team/IM service.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT HUP INT TERM
java=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
javac=${JAVAC:-${JAVA_HOME:+$JAVA_HOME/bin/}javac}
version=$("$java" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)
case "$version" in
    1.8.*) default_flags='-source 8 -target 8' ;;
    *) default_flags='--release 8' ;;
esac
# A source/target override on newer JDKs is NOT a Java 8 API compatibility check.
flags=${JAVA_COMPILER_FLAGS:-$default_flags}
if command -v "$javac" >/dev/null 2>&1; then
    compile() { "$javac" "$@"; }
else
    case "$version" in
        1.8.*) echo 'A full JDK 8 with javac is required; set JAVA_HOME or JAVAC.' >&2; exit 1 ;;
    esac
    # Some bounded runtimes expose jdk.compiler but have no javac executable.
    compile() { "$java" -m jdk.compiler/com.sun.tools.javac.Main "$@"; }
fi
# Compile the unchanged real API/entity/VO sources. Only unrelated inherited metadata
# and serialization annotations are compile-only stubs; this is not a module build.
stubs="$out/stubs"
mkdir -p "$stubs/com/chengyinhub/common/core/domain" "$stubs/com/fasterxml/jackson/annotation"
cat > "$stubs/com/chengyinhub/common/core/domain/BaseEntity.java" <<'JAVA'
package com.chengyinhub.common.core.domain;
public class BaseEntity implements java.io.Serializable {}
JAVA
cat > "$stubs/com/fasterxml/jackson/annotation/JsonFormat.java" <<'JAVA'
package com.fasterxml.jackson.annotation;
public @interface JsonFormat { String pattern() default ""; }
JAVA
cat > "$stubs/com/fasterxml/jackson/annotation/JsonIgnore.java" <<'JAVA'
package com.fasterxml.jackson.annotation;
public @interface JsonIgnore {}
JAVA
"$java" -version
# Word splitting of flags is intentional to preserve the existing compiler override.
compile $flags -d "$out/classes" \
 "$stubs/com/chengyinhub/common/core/domain/BaseEntity.java" \
 "$stubs/com/fasterxml/jackson/annotation/JsonFormat.java" \
 "$stubs/com/fasterxml/jackson/annotation/JsonIgnore.java" \
 "$root/chengyinhub-system/src/main/java/com/chengyinhub/business/domain/PlayTeam.java" \
 "$root/chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/PlayTeamNearbyVO.java" \
 "$root/chengyinhub-system/src/main/java/com/chengyinhub/business/service/IPlayTeamService.java" \
 "$root/chengyinhub-system/src/main/java/com/chengyinhub/business/service/rendezvous/RendezvousConsentService.java" \
 "$root/chengyinhub-system/src/main/java/com/chengyinhub/business/service/rendezvous/RendezvousMeetingService.java" \
 "$root/chengyinhub-system/src/main/java/com/chengyinhub/business/service/rendezvous/ExistingPlayTeamMeetingHandoff.java" \
 "$root/chengyinhub-admin/src/test/java/com/chengyinhub/business/service/rendezvous/RendezvousMeetingServiceTest.java"
"$java" -cp "$out/classes" com.chengyinhub.business.service.rendezvous.RendezvousMeetingServiceTest
