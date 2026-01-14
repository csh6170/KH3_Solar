import sys
import pandas as pd
import joblib
import json
import os
import math  # 📐 수학 계산용 (Bell Curve)
# import numpy as np  # [삭제됨] 굳이 무거운 numpy를 쓰지 않고 내장 math 모듈로 대체함

# ---------------------------------------------------------
# 1. 모델 로드
# ---------------------------------------------------------
current_folder = os.path.dirname(os.path.abspath(__file__))
model_path = os.path.join(current_folder, 'data', 'solar_model.pkl')

if not os.path.exists(model_path):
    print(json.dumps({"error": "모델 파일을 찾을 수 없습니다."}))
    sys.exit(1)

model = joblib.load(model_path)

# ---------------------------------------------------------
# 2. 입력값 받기 (테스트 모드 vs 자바 연동 모드)
# ---------------------------------------------------------
try:
    # 인자가 부족하면 테스트용 기본값 사용
    if len(sys.argv) < 11:
        base_temp = 20.0
        cloud = 5.0
        wind = 2.0
        humidity = 60.0
        base_sunshine = 0.5
        base_radiation = 2.5
        snow = 0.0
        rain = 0.0
        lat = 37.0507   # 기본값 (당진)
        lon = 126.5103  # 기본값
    else:
        # 자바에서 보낸 데이터 수신
        base_temp = float(sys.argv[1])
        cloud = float(sys.argv[2])
        wind = float(sys.argv[3])
        humidity = float(sys.argv[4])
        base_sunshine = float(sys.argv[5])
        base_radiation = float(sys.argv[6])
        snow = float(sys.argv[7])
        rain = float(sys.argv[8])
        lat = float(sys.argv[9])
        lon = float(sys.argv[10])

    # ---------------------------------------------------------
    # 3. 시간대별 예측 (Bell Curve 적용)
    # ---------------------------------------------------------
    
    # [삭제됨] 기존 코드에서는 여기서 첫 번째 루프를 돌리고...
    # total_daily_efficiency = 0.0
    # for hour in range(6, 20):
    #     normalized_time = (hour - 6) / 14 * np.pi ... (numpy 사용)
    #     ... (예측 수행) ...
    #     total_daily_efficiency += pred

    # [수정 후] 하나의 변수 세트로 초기화
    total_daily_efficiency = 0.0
    hourly_results = []

    # 06시 ~ 19시까지 태양의 움직임 시뮬레이션
    for hour in range(6, 20): 
        
        # [핵심 로직] 시간대별 날씨 보정 (Bell Curve)
        # -----------------------------------------------------
        
        # 1) 태양 고도 효율 계수 (13시 피크)
        # [삭제됨] normalized_time = (hour - 6) / 14 * np.pi 
        # [수정 후] math 모듈 사용
        time_factor = math.sin((hour - 6) * math.pi / 14)
        if time_factor < 0: time_factor = 0

        # 2) 변수 보정
        curr_radiation = base_radiation * time_factor  # 일사량 보정
        curr_sunshine = base_sunshine * time_factor    # 일조량 보정

        # 기온 보정: 14시 기준 시간차만큼 기온을 살짝 뺌 (0.3도씩)
        curr_temp = base_temp - (abs(hour - 14) * 0.3) 

        # -----------------------------------------------------

        # 모델에 넣을 데이터 프레임 생성
        input_data = pd.DataFrame({
            '시간': [hour],
            '위도': [lat],
            '경도': [lon],
            'temp': [curr_temp],
            'rain': [rain],
            'wind': [wind],
            'humidity': [humidity],
            'sunshine': [curr_sunshine],
            'radiation': [curr_radiation],
            'snow': [snow],
            'cloud': [cloud]
        })

        # 예측 실행
        pred = model.predict(input_data)[0]
        
        # 마이너스 값 보정
        if pred < 0: pred = 0.0
            
        total_daily_efficiency += pred
        
        # 결과 리스트에 저장
        hourly_results.append({
            "hour": hour,
            "value": round(pred, 3)
        })

    # [삭제됨] 기존 코드에서는 여기서 두 번째 루프(중복)가 또 있었습니다.
    # hourly_results = []
    # for hour in range(6, 20):
    #     ... (위와 똑같은 계산 반복) ...
    #     hourly_results.append(...)
    # -> [수정] 위에서 한 번의 루프로 계산과 저장을 동시에 끝냈으므로 삭제함.

    # ---------------------------------------------------------
    # 4. 결과 JSON 출력
    # ---------------------------------------------------------
    final_result = {
        "total": round(total_daily_efficiency, 4),
        "hourly": hourly_results
    }
    
    # JSON 문자열 출력 (Java가 읽는 부분)
    print(json.dumps(final_result))

except Exception as e:
    # 에러 발생 시 JSON 포맷으로 에러 전달
    error_msg = {"error": str(e)}
    print(json.dumps(error_msg))