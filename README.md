# pbench
This is a Maven Java Project.

Build: mvn package

Run:
Go to pbench directory and execute:
java -Djava.ext.dirs=target/lib -jar target/PBench-1.0-SNAPSHOT.jar -t 1000000 -l 2 -v 3 -q 20 "http://localhost:8005/users?search=suggested&filter=no&with=contacts&@{user_id}=100&limit=2000"

Parameter read from 'param.txt' file.

Arguments Specification:
-t: test time in seconds
-d: send request in ms of one second, value range [1, 1000]
-q: QPS
-v: view response content
-l: print info every N loops