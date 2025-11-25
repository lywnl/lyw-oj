rm D:\OJ\lyw-oj\deploy\prod\lywoj-jar/gateway/oj-gateway.jar
rm D:\OJ\lyw-oj\deploy\prod\lywoj-jar/friend/oj-friend.jar
rm D:\OJ\lyw-oj\deploy\prod\lywoj-jar/job/oj-job.jar
rm D:\OJ\lyw-oj\deploy\prod\lywoj-jar/judge/oj-judge.jar
rm D:\OJ\lyw-oj\deploy\prod\lywoj-jar/system/oj-system.jar

copy D:\OJ\lyw-oj/oj-gateway/target/oj-gateway-1.0-SNAPSHOT.jar D:\OJ\lyw-oj\deploy\prod\lywoj-jar/gateway/oj-gateway.jar
copy D:\OJ\lyw-oj/oj-modules/oj-judge/target/oj-judge-1.0-SNAPSHOT.jar D:\OJ\lyw-oj\deploy\prod\lywoj-jar/judge/oj-judge.jar
copy D:\OJ\lyw-oj/oj-modules/oj-friend/target/oj-friend-1.0-SNAPSHOT.jar D:\OJ\lyw-oj\deploy\prod\lywoj-jar/friend/oj-friend.jar
copy D:\OJ\lyw-oj/oj-modules/oj-job/target/oj-job-1.0-SNAPSHOT.jar D:\OJ\lyw-oj\deploy\prod\lywoj-jar/job/oj-job.jar
copy D:\OJ\lyw-oj/oj-modules/oj-system/target/oj-system-1.0-SNAPSHOT.jar D:\OJ\lyw-oj\deploy\prod\lywoj-jar/system/oj-system.jar
pause