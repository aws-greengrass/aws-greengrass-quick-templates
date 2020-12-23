#!/bin/sh
cd $(dirname $0)
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
EOFY
echo Installed in $CMD