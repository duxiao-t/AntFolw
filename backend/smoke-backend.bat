@echo off
set PORT=8091
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/antflow?stringtype=unspecified
set SPRING_DATASOURCE_USERNAME=postgres
set SPRING_DATASOURCE_PASSWORD=Tao@1234
set JWT_SECRET=devsecret0123456789devsecret0123456789devsecret
set MOBILE_FILE_STORAGE=minio
set MINIO_ENDPOINT=http://localhost:9000
set MINIO_ACCESS_KEY=minioadmin
set MINIO_SECRET_KEY=minioadmin
set MINIO_BUCKET=antflow-mobile-files
cd /d C:\Users\Administrator\.config\superpowers\worktrees\ant-flow\mobile-approval-workflow\backend
java -jar target\antflow-backend-0.1.0-SNAPSHOT.jar > C:\Users\Administrator\.config\superpowers\worktrees\ant-flow\mobile-approval-workflow\backend\smoke-backend.log 2> C:\Users\Administrator\.config\superpowers\worktrees\ant-flow\mobile-approval-workflow\backend\smoke-backend.err.log
