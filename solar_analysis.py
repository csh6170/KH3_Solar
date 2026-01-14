import pandas as pd
import numpy as np
import os

def preprocess_solar_data(file_path):
    """
    태양광 발전량 데이터를 불러와 전처리를 수행하는 함수
    """
    
    # 1. 데이터 로드
    print(f"Loading data from {file_path}...")
    
    # [수정된 부분] encoding='cp949' 옵션 추가!
    # 한국어(한글)가 포함된 CSV 파일은 반드시 이 옵션이 필요합니다.
    try:
        df = pd.read_csv(file_path, encoding='cp949')
    except UnicodeDecodeError:
        # 혹시 cp949로도 안 되면 euc-kr로 시도
        print("cp949 failed, trying euc-kr...")
        df = pd.read_csv(file_path, encoding='euc-kr')
    
    # ---------------------------------------------------------
    # 2. 데이터 정제 (분석된 특이점 제거)
    # ---------------------------------------------------------
    
    # [제거 1] 동해바이오화력본부 태양광
    df = df[df['발전기명'] != '동해바이오화력본부 태양광']
    
    # [제거 2] 2024-04-15 당진화력수상태양광
    error_mask = (df['날짜'] == '2024-04-15') & (df['발전기명'] == '당진화력수상태양광')
    df = df[~error_mask]
    
    # [제거 3] 발전량이 아예 없는 날
    hourly_cols = [f'{i:02d}시' for i in range(1, 25)]
    df['Daily_Total'] = df[hourly_cols].sum(axis=1)
    df = df[df['Daily_Total'] > 0].copy()
    
    print(f"Data cleaned. Remaining rows: {len(df)}")

    # ---------------------------------------------------------
    # 3. 데이터 구조 변환 (Wide -> Long)
    # ---------------------------------------------------------
    id_vars = ['날짜', '발전기명', '설비용량(메가와트)', '위도', '경도']
    df_long = df.melt(id_vars=id_vars, value_vars=hourly_cols, 
                      var_name='시간_str', value_name='발전량_Wh')

    df_long['시간'] = df_long['시간_str'].str.replace('시', '').astype(int)
    
    # ---------------------------------------------------------
    # 4. 날짜/시간 형식 표준화
    # ---------------------------------------------------------
    def convert_to_datetime(row):
        date_obj = pd.to_datetime(row['날짜'])
        hour = row['시간']
        if hour == 24:
            return date_obj + pd.Timedelta(days=1)
        else:
            return date_obj + pd.Timedelta(hours=hour)

    df_long['일시'] = df_long.apply(convert_to_datetime, axis=1)

    # ---------------------------------------------------------
    # 5. 타겟 변수 생성
    # ---------------------------------------------------------
    df_long['설비용량_kW'] = df_long['설비용량(메가와트)'] * 1000
    df_long['발전량_kWh'] = df_long['발전량_Wh'] / 1000
    df_long['target_y'] = df_long['발전량_kWh'] / df_long['설비용량_kW']
    
    final_cols = ['일시', '날짜', '시간', '발전기명', '위도', '경도', 'target_y']
    df_final = df_long[final_cols].sort_values(['발전기명', '일시'])
    
    return df_final

# =========================================================
# 실행 부분
# =========================================================
if __name__ == "__main__":
    
    # 1. 현재 폴더 위치 파악
    current_folder = os.path.dirname(os.path.abspath(__file__))
    
    # 2. data 폴더 경로 연결
    input_file = os.path.join(current_folder, 'data', '한국동서발전(주)_일자별지점별 태양광 발전량 데이터_20250630 (동해 빠진 버전).csv')
    output_file = os.path.join(current_folder, 'data', 'processed_solar_data.csv')

    # 3. 실행
    if not os.path.exists(input_file):
        print(f"❌ 에러: 파일을 찾을 수 없습니다!")
        print(f"찾는 위치: {input_file}")
    else:
        # 전처리 실행
        clean_df = preprocess_solar_data(input_file)
        
        # 결과 확인
        print("\n[Preview of Processed Data]")
        print(clean_df.head())
        
        # 저장
        clean_df.to_csv(output_file, index=False)
        print(f"\n✅ 저장 완료! '{output_file}' 파일을 확인하세요.")