PID=$(ps -ef | grep chengyinhub-admin.jar| grep -v grep | awk '{ print $2 }')
if [ -z "$PID" ]
then
  echo  chengyinhub-admin is already stopped
else
  echo kill -9 $PID
  kill -9 $PID
fi


nohup java -jar chengyinhub-admin.jar 1>catalina_chengyinhub-admin.out 2>&1 & 
tail -100f catalina_chengyinhub-admin.out  
