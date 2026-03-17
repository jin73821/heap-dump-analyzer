#!/bin/bash
# 파일명: git_push.sh

cd /opt/genspark/webapp_dump

echo "변경사항 확인 중..."
git status

echo "파일 추가 중..."
git add .

echo "커밋 메시지를 입력하세요:"
read commit_message

git commit -m "$commit_message"
git push origin main

echo "Push 완료!"

