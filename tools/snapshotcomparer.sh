#!/usr/bin/env bash

# This file is part of VoltDB.

# Copyright (C) 2008-2021 VoltDB Inc.
#
# This file contains original code and/or modifications of original code.
# Any modifications made by VoltDB Inc. are licensed under the following
# terms and conditions:
#
# Permission is hereby granted, free of charge, to any person obtaining
# a copy of this software and associated documentation files (the
# "Software"), to deal in the Software without restriction, including
# without limitation the rights to use, copy, modify, merge, publish,
# distribute, sublicense, and/or sell copies of the Software, and to
# permit persons to whom the Software is furnished to do so, subject to
# the following conditions:
#
# The above copyright notice and this permission notice shall be
# included in all copies or substantial portions of the Software.

# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
# IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
# OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
# ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
# OTHER DEALINGS IN THE SOFTWARE.

# Original license for parts of this script copied from or influenced by
# the Hadoop startup script:
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# The VoltDB snapshotcomparer script
#
# Environment Variables
#   JAVA_HOME        The java implementation to use.  Overrides JAVA_HOME.
#   JAVA_HEAP_MAX    The maximum amount of heap to use, in MB. Default is 1024.
#   VOLTDB_OPTS     Extra Java runtime options.
#   LOG4J_CONFIG_PATH     Path to alternate log4j configuration

# resolve symlinks and canonicalize the path (make it absolute)
pushd . > /dev/null
this=$0
cd `dirname $this`
this=`basename $this`
while [ -L "$this" ]
do
    this=`readlink $this`
    cd `dirname $this`
    this=`basename $this`
done
this="$(pwd -P)/$this"
popd > /dev/null
# the root of the VoltDB installation
export VOLTDB_HOME=$(dirname $(dirname "$this"))

VOLTDB_VOLTDB=$VOLTDB_HOME/voltdb
VOLTDB_LIB=$VOLTDB_HOME/lib

JAVA=`which java`
if [ x"$JAVA_HEAP_MAX" = "x" ]
then
    JAVA_HEAP_MAX=-Xmx2048m
fi

# some Java parameters
if [ "$JAVA_HOME" != "" ]; then
  #echo "run java in $JAVA_HOME"
  JAVA=$JAVA_HOME/bin/java
fi

if [ "$JAVA" = "" ]; then
  echo "Couldn't find java version to run (make sure JAVA_HOME is set)."
  exit 1
fi

# check envvars to see if a user overrides log4j conf
# otherwise apply defaults
if [ -z "${LOG4J_CONFIG_PATH}" ]; then
  if [ -f "$VOLTDB_HOME/voltdb/utility-log4j.xml" ]; then
    LOG4J_CONFIG_PATH=$VOLTDB_HOME/voltdb/utility-log4j.xml
  elif [ -f "$VOLTDB_HOME/voltdb/log4j.xml" ]; then
    LOG4J_CONFIG_PATH=$VOLTDB_HOME/voltdb/log4j.xml
  else
    echo "Couldn't find log4j configuration file."
    exit 1
  fi
fi

VOLTDBJAR=`ls $VOLTDB_VOLTDB/voltdb-*.*.jar | grep -v "doc.jar" | head -1`
if [ -n "${VOLTDBJAR}" ]; then
  CLASSPATH=$VOLTDBJAR
else
  echo "Couldn't find compiled VoltDB jar to run."
  exit 1
fi

# add libs to CLASSPATH
for f in $VOLTDB_LIB/*.jar; do
  CLASSPATH=${CLASSPATH}:$f;
done

CLASSPATH=${CLASSPATH}

# run it
export CMDVAL="$JAVA -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djava.awt.headless=true $JAVA_HEAP_MAX $VOLTDB_OPTS -Dlog4j.configuration=file://${LOG4J_CONFIG_PATH} "
export CMDVAL="${CMDVAL}-classpath $CLASSPATH org.voltdb.utils.SnapshotComparer"
#echo $CMDVAL "$@"
exec $CMDVAL "$@"
