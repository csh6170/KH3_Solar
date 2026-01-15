import subprocess
import sys
import time
import os
import signal

# 파이썬 실행기 경로 (현재 실행 중인 파이썬 경로를 그대로 사용)
PYTHON_EXE = sys.executable 

# 실행할 파일들의 경로
script1 = "ai_server.py"
script2 = "predict.py"

print(f"🚀 통합 서버 매니저를 시작합니다...")
print(f"python: {PYTHON_EXE}")

processes = []

try:
    # 1. AI 서버 실행 (FastAPI)
    print(f"run >> {script1}")
    p1 = subprocess.Popen([PYTHON_EXE, script1])
    processes.append(p1)

    # 2. 텔레그램 봇 실행 (Predict Bot)
    print(f"run >> {script2}")
    p2 = subprocess.Popen([PYTHON_EXE, script2])
    processes.append(p2)

    print("\n✅ 두 서비스가 모두 실행되었습니다. 종료하려면 Ctrl+C를 누르세요.\n")
    
    # 메인 프로세스가 종료되지 않도록 무한 대기
    while True:
        time.sleep(1)

except KeyboardInterrupt:
    print("\n🛑 종료 요청(Ctrl+C) 감지! 모든 프로세스를 정리합니다...")

finally:
    # 종료 시 자식 프로세스들도 함께 Kill
    for p in processes:
        if p.poll() is None: # 아직 실행 중이라면
            print(f"killing process {p.pid}...")
            p.terminate() # 또는 p.kill()
    
    print("👋 모든 서버가 안전하게 종료되었습니다.")