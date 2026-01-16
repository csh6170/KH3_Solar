import subprocess
import sys
import time
import os

# 인코딩 문제 방지를 위해 표준 출력을 UTF-8로 설정
sys.stdout.reconfigure(encoding='utf-8')

PYTHON_EXE = sys.executable
script1 = "ai_server.py"
script2 = "predict.py"

# 설치할 라이브러리 목록
required_packages = [
    "fastapi",
    "uvicorn",
    "pandas",
    "scikit-learn",
    "joblib",
    "requests",
    "geopy",
    "python-telegram-bot",
    "numpy"
]

print("--- Manager Start ---")

# ---------------------------------------------------------
# 1. 라이브러리 자동 설치 (추가된 부분)
# ---------------------------------------------------------
print("Checking and installing libraries...")
try:
    # 현재 실행 중인 파이썬(sys.executable)의 pip 모듈을 호출합니다.
    # check=True는 설치 중에 에러가 나면 스크립트를 중단시킵니다.
    subprocess.check_call([PYTHON_EXE, "-m", "pip", "install"] + required_packages)
    print("Library installation complete.")
    print("--------------------------------")
except subprocess.CalledProcessError as e:
    print(f"Error occurred during library installation: {e}")
    sys.exit(1) # 설치 실패 시 프로그램 종료

# ---------------------------------------------------------
# 2. 서버 및 봇 실행 (기존 코드)
# ---------------------------------------------------------
processes = []

def kill_child_processes():
    for p in processes:
        if p.poll() is None:
            print(f"Stopping {p.pid}...")
            # 윈도우에서 더 확실하게 죽이기 위해 taskkill 사용
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)

try:
    # 파이썬 서버 실행
    print(f"Starting {script1}...")
    p1 = subprocess.Popen([PYTHON_EXE, script1])
    processes.append(p1)

    # 텔레그램 봇 실행
    print(f"Starting {script2}...")
    p2 = subprocess.Popen([PYTHON_EXE, script2])
    processes.append(p2)

    print("All servers are running. Monitoring parent process...")

    while True:
        time.sleep(2)
        # 두 프로세스 중 하나라도 죽으면 매니저도 종료 (또는 재시작 로직 추가 가능)
        if p1.poll() is not None or p2.poll() is not None:
            print("One of the processes has stopped. Shutting down...")
            break
except Exception as e:
    print(f"Error: {e}")
except KeyboardInterrupt:
    print("User stopped the manager.")
finally:
    kill_child_processes()
    print("Cleanup complete.")