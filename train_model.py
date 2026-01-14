import pandas as pd
import numpy as np
import os
import joblib  # 모델 저장을 위한 라이브러리
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import r2_score, mean_absolute_error

def train_solar_model():
    print("🚀 AI 모델 학습을 시작합니다...")

    # ---------------------------------------------------------
    # 1. 데이터 불러오기
    # ---------------------------------------------------------
    current_folder = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.join(current_folder, 'data', 'final_dataset.csv')
    
    if not os.path.exists(data_path):
        print("❌ 에러: final_dataset.csv 파일이 없습니다. Step 2를 먼저 진행하세요.")
        return

    df = pd.read_csv(data_path)
    print(f"✅ 데이터 로드 완료: {len(df)}개 샘플")

    # ---------------------------------------------------------
    # 2. 문제(X)와 정답(y) 나누기
    # ---------------------------------------------------------
    # 학습에 사용할 특징(Features) 선택
    # 시간, 위도/경도, 기온, 강수, 풍속, 습도, 일조, 일사, 적설, 전운량
    features = ['시간', '위도', '경도', 'temp', 'rain', 'wind', 'humidity', 'sunshine', 'radiation', 'snow', 'cloud']
    target = 'target_y'  # 우리가 맞출 것 (1kW당 발전량)

    X = df[features]
    y = df[target]

    # ---------------------------------------------------------
    # 3. 훈련용(Train) vs 시험용(Test) 데이터 나누기
    # ---------------------------------------------------------
    # 전체 데이터의 80%로 공부하고, 20%로 나중에 시험 봅니다.
    print("✂️ 데이터를 훈련용(80%)과 시험용(20%)으로 나누는 중...")
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    # ---------------------------------------------------------
    # 4. 모델 생성 및 학습 (여기가 핵심!)
    # ---------------------------------------------------------
    print("🧠 랜덤 포레스트 모델이 학습을 시작합니다... (시간이 조금 걸릴 수 있어요)")
    
    # n_estimators=100 : 나무 100그루를 심겠다는 뜻
    model = RandomForestRegressor(n_estimators=100, random_state=42, n_jobs=-1)
    model.fit(X_train, y_train)
    
    print("✅ 학습 완료!")

    # ---------------------------------------------------------
    # 5. 성능 평가 (시험 보기)
    # ---------------------------------------------------------
    print("📝 성능을 평가합니다...")
    y_pred = model.predict(X_test)

    # R2 Score (결정 계수): 1.0에 가까울수록 완벽하게 맞춘 것
    score = r2_score(y_test, y_pred)
    # MAE (평균 절대 오차): 예측이 평균적으로 얼마나 빗나갔는지
    mae = mean_absolute_error(y_test, y_pred)

    print(f"\n[📊 성적표]")
    print(f"accuracy (R2 Score): {score:.4f} (1.0 만점)")
    print(f"오차 (MAE): {mae:.4f} kWh (이만큼 틀릴 수 있음)")

    if score > 0.8:
        print("🎉 와우! 아주 훌륭한 모델입니다!")
    elif score > 0.6:
        print("🙂 꽤 괜찮은 성능입니다.")
    else:
        print("🤔 성능을 좀 더 높여야겠네요.")

    # ---------------------------------------------------------
    # 6. 모델 저장하기 (나중에 써먹기 위해)
    # ---------------------------------------------------------
    save_path = os.path.join(current_folder, 'data', 'solar_model.pkl')
    joblib.dump(model, save_path)
    print(f"\n💾 모델이 저장되었습니다: {save_path}")
    print("이제 이 파일만 있으면 언제든 발전량을 예측할 수 있습니다!")

if __name__ == "__main__":
    train_solar_model()