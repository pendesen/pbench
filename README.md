# pbench
This is a Maven Java Project.

Run:
pass in the program argument like:
-t 10 -l 3 -q 300 "http://10.189.100.42:8004/users?search=n&with=ns&byPassThroughMode=false&@{user_id}=1&limit=2000" 

Arguments Specification:
-t: test time in seconds
-d: send request in ms of one second, value range [1, 1000]
-q: QPS
-v: view response content
-l: print info every N loops