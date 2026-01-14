import pandas as pd
import glob
import os

def merge_all_data():
    print("🚀 데이터 병합을 시작합니다...")
    
    # ---------------------------------------------------------
    # 1. 태양광 데이터 불러오기 (Step 1에서 만든 것)
    # ---------------------------------------------------------
    current_folder = os.path.dirname(os.path.abspath(__file__))
    solar_path = os.path.join(current_folder, 'data', 'processed_solar_data.csv')
    
    if not os.path.exists(solar_path):
        print(f"❌ 에러: {solar_path} 파일이 없습니다. Step 1 코드를 먼저 실행하세요!")
        return

    solar_df = pd.read_csv(solar_path)
    solar_df['일시'] = pd.to_datetime(solar_df['일시'])
    print(f"✅ 태양광 데이터 로드 완료: {len(solar_df)}개 행")

    # ---------------------------------------------------------
    # 2. 날씨 데이터 불러오기 & 합치기 함수
    # ---------------------------------------------------------
    def load_weather_by_region(region_name):
        # 파일 찾기 (예: data/weather_seosan_*.csv)
        search_pattern = os.path.join(current_folder, 'data', f'weather_{region_name}_*.csv')
        files = glob.glob(search_pattern)
        
        if not files:
            print(f"⚠️ 경고: {region_name} 날씨 파일을 하나도 못 찾았습니다!")
            return pd.DataFrame()
            
        print(f"📂 {region_name} 날씨 파일 {len(files)}개를 합치는 중...")
        
        df_list = []
        for f in files:
            # UTF-8로 시도해보고 안되면 CP949로 읽기 (안전장치)
            try:
                df = pd.read_csv(f, encoding='utf-8')
            except UnicodeDecodeError:
                df = pd.read_csv(f, encoding='cp949')
            df_list.append(df)
            
        merged_weather = pd.concat(df_list, ignore_index=True)
        return preprocess_weather(merged_weather)

    # ---------------------------------------------------------
    # 3. 날씨 데이터 전처리 (영어 컬럼명 변경 & 결측치 처리)
    # ---------------------------------------------------------
    def preprocess_weather(df):
        # 컬럼 이름 변경 (한글 -> 영어)
        col_map = {
            '일시': '일시',
            '기온(°C)': 'temp',
            '강수량(mm)': 'rain',
            '풍속(m/s)': 'wind',
            '습도(%)': 'humidity',
            '일조(hr)': 'sunshine',
            '일사(MJ/m2)': 'radiation',
            '적설(cm)': 'snow',
            '전운량(10분위)': 'cloud'
        }
        df = df.rename(columns=col_map)
        df['일시'] = pd.to_datetime(df['일시'])
        
        # 결측치(NaN) 채우기 (아주 중요!)
        df['rain'] = df['rain'].fillna(0)       # 비 안 오면 0
        df['snow'] = df['snow'].fillna(0)       # 눈 안 오면 0
        df['sunshine'] = df['sunshine'].fillna(0) # 밤에는 일조량 0
        df['radiation'] = df['radiation'].fillna(0) # 밤에는 일사량 0
        df['cloud'] = df['cloud'].fillna(method='ffill').fillna(0) # 구름은 직전 값으로 채움
        df['humidity'] = df['humidity'].fillna(method='ffill') # 습도도 직전 값으로
        
        # 필요한 컬럼만 선택
        selected_cols = ['일시', 'temp', 'rain', 'wind', 'humidity', 'sunshine', 'radiation', 'snow', 'cloud']
        return df[selected_cols]

    # 각 지역 날씨 불러오기
    weather_seosan = load_weather_by_region('seosan') # 당진용
    weather_ulsan = load_weather_by_region('ulsan')   # 울산용
    
    # ---------------------------------------------------------
    # 4. 발전소 위치에 맞춰 날씨 데이터 병합 (Merge)
    # ---------------------------------------------------------
    print("🔗 태양광 데이터와 날씨 데이터를 연결하는 중...")
    
    # 당진 발전소들 -> 서산 날씨와 연결
    dangjin_gens = ['당진자재창고태양광', '당진태양광', '당진화력수상태양광']
    solar_dangjin = solar_df[solar_df['발전기명'].isin(dangjin_gens)].copy()
    merged_dangjin = pd.merge(solar_dangjin, weather_seosan, on='일시', how='inner')
    
    # 울산 발전소 -> 울산 날씨와 연결
    ulsan_gens = ['울산태양광#1']
    solar_ulsan = solar_df[solar_df['발전기명'].isin(ulsan_gens)].copy()
    merged_ulsan = pd.merge(solar_ulsan, weather_ulsan, on='일시', how='inner')
    
    # 두 결과 합치기
    final_df = pd.concat([merged_dangjin, merged_ulsan], ignore_index=True)
    final_df = final_df.sort_values(['발전기명', '일시'])
    
    # ---------------------------------------------------------
    # 5. 최종 저장
    # ---------------------------------------------------------
    save_path = os.path.join(current_folder, 'data', 'final_dataset.csv')
    final_df.to_csv(save_path, index=False, encoding='utf-8-sig')
    
    print(f"\n🎉 성공! 모든 데이터가 하나로 합쳐졌습니다.")
    print(f"💾 저장 위치: {save_path}")
    print(f"📊 총 데이터 개수: {len(final_df)}개")
    print(final_df.head())

if __name__ == "__main__":
    merge_all_data()