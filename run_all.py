import subprocess
import sys
import time
import os

# 인코딩 문제 방지를 위해 표준 출력을 UTF-8로 설정
sys.stdout.reconfigure(encoding='utf-8')

PYTHON_EXE = sys.executable
script1 = "ai_server.py"
script2 = "predict.py"

print("--- Manager Start ---") # 이모지 제거

processes = []

def kill_child_processes():
    for p in processes:
        if p.poll() is None:
            print(f"Stopping {p.pid}...")
            # 윈도우에서 더 확실하게 죽이기 위해 taskkill 사용
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)

try:
    # 파이썬 서버 실행
    p1 = subprocess.Popen([PYTHON_EXE, script1])
    processes.append(p1)

    # 텔레그램 봇 실행
    p2 = subprocess.Popen([PYTHON_EXE, script2])
    processes.append(p2)

    print("Servers are running. Monitoring parent process...")

    while True:
        time.sleep(2)
        if p1.poll() is not None or p2.poll() is not None:
            break
except Exception as e:
    print(f"Error: {e}")
finally:
    kill_child_processes()
    print("Cleanup complete.")
