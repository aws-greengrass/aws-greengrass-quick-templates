#! /bin/sh -x
# build "install.sh" from the build artifacts.
# To install ggqt, just:
# 
cd $(dirname $0)
#pwd
cat>install.sh<<'EOF'
#!/bin/sh
DEST=${1:-/usr/local}
if [ -z "$JAVA_HOME" ] ; then
  JAVACMD=`which java`
else
  JAVACMD="$JAVA_HOME/bin/java"
fi
mkdir -p $DEST/bin
mkdir -p $DEST/lib
CMD=$DEST/bin/ggqt
cat>$CMD <<XEOF
#!/bin/sh
exec "${JAVACMD:-java}" -jar $DEST/lib/ggqt.jar "\$@"
XEOF
chmod a+x $CMD
base64 -d <<EOFY >$DEST/lib/ggqt.jar
EOF
#pwd;ls target
cat target/*cies.jar|base64>>install.sh
cat>>install.sh<<'XEOF'
EOFY
echo Installed in $CMD
XEOF
echo Your install is now in install.sh
echo '"sh install.sh"' to install ggqt
