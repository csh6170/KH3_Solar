import pandas as pd
import os

# 1. 파일 이름 설정 (현재 폴더에 있는 파일명과 정확히 일치해야 함)
# 만약 파일명이 다르다면 아래 변수를 수정해주세요.
input_filename = '기상청41_단기예보 조회서비스_오픈API활용가이드_격자_위경도(2510).xlsx' 
# 혹시 파일명이 .csv로 끝난다면 아래 주석을 풀고 사용하세요
# input_filename = '기상청41_단기예보 조회서비스_오픈API활용가이드_격자_위경도(2510).xlsx - 최종 업데이트 파일_20251027.csv'

output_filename = 'weather_location.csv'

def convert_file():
    print(f"🔄 '{input_filename}' 파일 변환을 시작합니다...")

    if not os.path.exists(input_filename):
        print(f"❌ 오류: '{input_filename}' 파일을 찾을 수 없습니다.")
        print("   파일명과 확장자(.xlsx 또는 .csv)가 정확한지 확인해주세요.")
        return

    df = None

    # [시도 1] 엑셀 파일(.xlsx)로 읽기 시도 (가장 유력)
    try:
        print("   👉 엑셀 형식으로 읽기 시도 중...")
        df = pd.read_excel(input_filename, engine='openpyxl')
    except Exception as e_excel:
        print(f"   (엑셀 읽기 실패: {e_excel})")
        
        # [시도 2] CSV 파일로 읽기 시도 (인코딩 바꿔가며)
        print("   👉 CSV 형식으로 읽기 재시도 중 (cp949)...")
        try:
            df = pd.read_csv(input_filename, encoding='cp949')
        except:
            print("   👉 CSV 형식으로 읽기 재시도 중 (utf-8)...")
            try:
                df = pd.read_csv(input_filename, encoding='utf-8')
            except Exception as e_csv:
                print(f"❌ 오류: 파일을 읽을 수 없습니다. 엑셀 파일이 암호화되어 있거나 손상되었는지 확인해주세요.")
                return

    # 데이터 처리가 성공적으로 되었으면 변환 작업 수행
    try:
        # 필요한 컬럼만 선택
        needed_columns = ['1단계', '2단계', '3단계', '격자 X', '격자 Y', '위도(초/100)', '경도(초/100)']
        
        # 컬럼 존재 여부 확인
        missing_cols = [col for col in needed_columns if col not in df.columns]
        if missing_cols:
            print(f"❌ 오류: 파일 내에 다음 컬럼을 찾을 수 없습니다: {missing_cols}")
            print(f"   현재 파일 컬럼 목록: {df.columns.tolist()}")
            return

        df_clean = df[needed_columns].copy()

        # 컬럼 이름 영문 변경
        df_clean.columns = ['Region1', 'Region2', 'Region3', 'nx', 'ny', 'lat', 'lon']

        # 결측치 처리 및 공백 제거
        df_clean = df_clean.fillna('')
        cols_to_strip = ['Region1', 'Region2', 'Region3']
        # 문자열인 경우에만 strip 적용
        for col in cols_to_strip:
            df_clean[col] = df_clean[col].astype(str).str.strip()
            # 'nan' 문자열이 된 경우 다시 빈칸으로
            df_clean.loc[df_clean[col] == 'nan', col] = ''

        # 최종 저장
        df_clean.to_csv(output_filename, index=False, encoding='utf-8-sig')

        print(f"\n✅ 변환 성공!")
        print(f"📂 생성된 파일: {output_filename}")
        print(f"📊 데이터 개수: {len(df_clean)}개")
        print("👉 이 파일을 스프링 프로젝트의 src/main/resources 폴더로 옮기세요.")

    except Exception as e:
        print(f"❌ 데이터 처리 중 오류 발생: {e}")

if __name__ == "__main__":
    convert_file()