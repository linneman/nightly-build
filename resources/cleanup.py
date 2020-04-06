# Python Script which cleans disc space
#
# Remove oldest (alphabetically first) subdirector  in given directory when free
# disc space falls below minimum required value.
#
# Invocation:
#
# python cleanup.py <root-dir> <required-disk-space in GB>
#
# June 2019@ol

import os, sys, glob, shutil, re, string
from functools import reduce
import subprocess
import datetime

# ---- Begin of Build Configuration Definitions ----


LOGFILENAME = os.getenv("HOME") + "/cleanup.log"
LOGFILEFP = open(LOGFILENAME, 'wt' )

# -----------------------------------------------------------------------------

INFOSTR = """Python Script which cleans disc space

Remove oldest (alphabetically first) subdirector  in given directory when free
disc space falls below minimum required value.

Invocation:

python cleanup.py <root-dir> <required-disk-space in GB>

June 2019@ol"""



def log(line):
    print(line)
    LOGFILEFP.write(line+'\n')


def shell_exec(cmd, interactive=False, write_to_log=True):

    if( interactive):
        cmd_array = ['/bin/sh', '-i', '-c']
    else:
        cmd_array = ['/bin/sh', '-c']

    if(write_to_log):
        log("execute cmd: '"+cmd+"' ...")
    cmd_array.append(cmd)
    # print(cmd_array)
    p = subprocess.Popen(cmd_array, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (stdout, stderr) = p.communicate()
    res = { "exit_code": p.returncode, "stdout": stdout, "stderr": stderr, "command": cmd }

    if(write_to_log):
        if( res["exit_code"] != 0 ):
            log("execution error in: "+cmd+" occurred, exit!")
            log("error was: "+res["stderr"])
        else:
            log("execution of cmd: '"+cmd+"' [OK]")
            log("---------------")

    return res


def get_free_space(filename):
    cmd = 'set -o pipefail && df -l --output=avail '+filename+' | tail -n1'
    res = shell_exec(cmd,write_to_log=False)

    if(res["exit_code"] != 0 ):
        log("execution of Unix system command df failed error!")
        free_space = -1
    else:
        free_space = int(res["stdout"])/1000000

    return(free_space)


def remove_dir(filename):
    cmd = 'rm -rf '+filename
    res = shell_exec(cmd,write_to_log=False)
    return(res["exit_code"])


def clean_builds(proj_root_dir, min_space_gb):

    try:
        builds = os.listdir(proj_root_dir)
    except OSError:
        log("build directory does not yet exist")
        return 0

    builds.sort()
    cleaned_subdirs = 0

    x = 20 # debug instrumentation

    for b in builds:

        avail_space = get_free_space( proj_root_dir )

        # x += 20
        # avail_space = x

        if( avail_space > min_space_gb ):
            break
        else:
            log("only "+str(avail_space)+"GB availabe, remove subdirectory " + b + " ...")
            res = remove_dir(proj_root_dir + "/" + b)
            # res = 0
            if( res != 0 ):
                return -1
            cleaned_subdirs += 1

    avail_space = get_free_space( proj_root_dir )
    if( avail_space < 0 ):
        return -1

    if( avail_space > min_space_gb ):
        log("we have "+str(avail_space)+"GB space available [OK]");
        return 0
    else:
        log("we have only "+str(avail_space)+"GB space available [NOT OK]");

    return -1



# main function:
#
# generate diff between latest release of specified pattern
# and write associated change log and semaphore files to
# root directory.

if( len(sys.argv) != 3 ):
    print(INFOSTR)
    # exit(-1)


PROJ_ROOT_DIR = sys.argv[1]
REQUIRED_SPACE = int(sys.argv[2])

# PROJ_ROOT_DIR = "/data/ol/development/nightly-builds/ltenad9607-bl2_2_0"
# REQUIRED_SPACE = 80


exit( clean_builds( PROJ_ROOT_DIR, REQUIRED_SPACE) )
