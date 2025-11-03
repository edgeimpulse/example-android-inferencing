#!/bin/bash
set -e

SCRIPTPATH="$( cd "$(dirname "$0")" ; pwd -P )"

mkdir -p $SCRIPTPATH/android64

cd $SCRIPTPATH/android64
if [ ! -f libtensorflow-lite.a ]; then
    wget -O tflite-android64.zip --show-progress https://cdn.edgeimpulse.com/build-system/tflite/android64/tflite-android64.zip
    # unzip files to android64 folder
    unzip tflite-android64.zip -d $SCRIPTPATH/android64
    rm tflite-android64.zip
fi