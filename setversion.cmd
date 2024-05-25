@echo off
REM Use this script in phiz-public directory to update the POM file with your feature branch
REM POM files with the branch and version name.  The ${project.version} property will be set to
REM 1.2.0-BranchName-SNAPSHOT
REM
REM The version field (1.2.0) should be set by the content in the Release_v* branch
REM
REM TO DO: Adjust this to work when HEAD is a Release_v* branch
REM

FOR /F "tokens=* USEBACKQ" %%F IN (`git rev-parse --abbrev-ref HEAD`) DO ( set IZGW_BRANCH=%%F )

REM User can force the base version by passing it in as an argument
REM which will allow them to use a different base release version  
if NOT "%1"=="" GOTO SKIPVERSION

git branch --list >%TEMP%\branches.tmp
FOR /F "tokens=* USEBACKQ" %%F IN (`FIND "Release_v" ^< %TEMP%\branches.tmp`) DO ( set IZGW_VERSION=%%F )
REM del %TEMP%\branches.tmp
GOTO SETVERSION

:SKIPVERSION
SET IZGW_VERSION=%1

REM Set the versioning
:SETVERSION
SET IZGW_BRANCH=%IZGW_BRANCH: =%
SET IZGW_VERSION=%IZGW_VERSION:**=%
SET IZGW_VERSION=%IZGW_VERSION:Release_v=%
SET IZGW_VERSION=%IZGW_VERSION:-branch=%
SET IZGW_VERSION=%IZGW_VERSION: =%
SET IZGW_REVISION=%IZGW_VERSION%-%IZGW_BRANCH%-SNAPSHOT
REM Show the mvn command to the user
@echo on
mvn versions:set -DnewVersion=%IZGW_REVISION% 
