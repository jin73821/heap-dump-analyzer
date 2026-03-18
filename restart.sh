ps -ef | grep heap-analyzer-2.0.0.jar | grep -v grep | awk '{print "kill -15 " $2}' | sh;
nohup java -jar /opt/genspark/webapp_dump/target/heap-analyzer-2.0.0.jar --server.port=18080 &
