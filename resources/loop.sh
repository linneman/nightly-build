#!/bin/sh
# prints N times a Welcome message to standard out

if [ "$#" -ne 1 ]; then
    END="1000"
else
    END=$1
fi


echoerr() { echo "$@" 1>&2; }

for i in $(eval echo "{1..$END}")
do
   echo "Welcome $i times"
   sleep 0.3

    n=$(($i%10))
    if [ $n == "0" ]; then
        echoerr "-> this is written to stderr"
    fi
done
