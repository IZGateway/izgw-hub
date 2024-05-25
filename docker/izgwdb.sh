#!/bin/sh

# This script will always perform a database backup. It is called by fatjar-run.sh and can
# also be included as a separate entry point to force a database backup.

cd /usr/share/izgateway


if [[ ! $MYSQL_HOST ]] 
then
   MYSQL_HOST=localhost
fi

if [[ ! $MYSQL_DB_NAME ]] 
then
   MYSQL_DB_NAME=phiz
fi

CMD=$1
if [[ ! $CMD ]]
then
   CMD=backup
fi

BACKUPNAME=$2
if [[ ! $BACKUPNAME ]]
then
    NAME=backup
fi

BACKUP_PREFIX=$BACKUPNAME-$MYSQL_DB_NAME
BACKUP=$BACKUP_PREFIX-`date +"%Y%m%d%H%M%S"`@`hostname`.sql

mkdir -p conf/backups

case $CMD in
  backup)
    BACKUP=$BACKUP_PREFIX-`date +"%Y%m%d%H%M%S"`@`hostname`.sql
    LOG="Backing up $MYSQL_HOST/$MYSQL_DB_NAME to conf/backups/$BACKUP. To restore database, run the following command: mysql --host=MYSQL_HOST --user=MYSQL_HUB_NAME --password=MYSQL_HUB_PASS --port=3306 --protocol=tcp MYSQL_DB_NAME < conf/backups/$BACKUP" 
    echo $LOG 
    echo $LOG >> conf/backups/izgwbackup.log 
    mysqldump --host=$MYSQL_HOST --password=$MYSQL_HUB_PASS --user=$MYSQL_HUB_NAME --port=3306 --protocol=tcp --max_allowed_packet=1G --triggers --routines $MYSQL_DB_NAME > conf/backups/$BACKUP 2>> conf/backups/izgwbackup.log 
    ;; 
   restore) 
    # Find MOST recent backup with matching BACKUPNAME and MYSQL_DB_NAME, or exact name (from any database) to restore
    BACKUP=`ls -1 -tc conf/backups/*$BACKUPNAME*-$MYSQL_DB_NAME*.sql conf/backups/$BACKUPNAME 2>/dev/null | head -1` 
    LOG="Restoring $MYSQL_HOST/$MYSQL_DB_NAME from conf/backups/$BACKUP"  
    echo $LOG 
    echo $LOG >> conf/backups/izgwbackup.log 
    mysql --password=$MYSQL_HUB_PASS --host=$MYSQL_HOST --user=$MYSQL_HUB_NAME --port=3306 --protocol=tcp --max_allowed_packet=1G $MYSQL_DB_NAME < $BACKUP 2>> conf/backups/izgwbackup.log 
    ;; 
  *) 
    LOG="Unrecognized izgwdb command: $CMD" 
    echo $LOG 
    echo $LOG >> conf/backups/izgwbackup.log 
    exit 1 
    ;;     
esac
