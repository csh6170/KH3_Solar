package com.solar;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class CalculatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalculatorApplication.class, args);
    }

}





// ============== AI 서버 프로세스 관리자 ==============
// 스프링 부트 실행 시 파이썬 서버를 켜고, 종료 시 같이 끕니다.

@Component
class AiServerManager {
    private Process pythonProcess;

    // 생성자에서 바로 실행하거나, @PostConstruct를 사용해도 됨
    public AiServerManager() {
        startAiServer();
    }


    private void startAiServer() {
        try {
            // 1. OS에 따라 python 또는 py 선택 (윈도우는 py가 더 확실할 때가 많음)
            String pythonCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";

            ProcessBuilder builder = new ProcessBuilder(pythonCmd, "run_all.py");
            builder.directory(new java.io.File(System.getProperty("user.dir")));

            // 중요: 에러 출력을 표준 출력과 합쳐서 Java 콘솔에서 바로 보이게 함
            builder.redirectErrorStream(true);
            builder.inheritIO();

            pythonProcess = builder.start();

            // 프로세스가 시작하자마자 죽었는지 확인하는 로직 추가
            Thread.sleep(1000);
            if (!pythonProcess.isAlive()) {
                System.err.println("❌ [Error] 파이썬 프로세스가 시작 직후 종료되었습니다. 종료 코드: " + pythonProcess.exitValue());
            } else {
                System.out.println("🚀 [Auto-Start] 서버 기동 성공 (PID: " + pythonProcess.pid() + ")");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 애플리케이션 종료 시 실행됨
    @PreDestroy
    public void stopAiServer() {
        if (pythonProcess != null && pythonProcess.isAlive()) {
            try {
                // 1. 현재 실행 중인 파이썬 프로세스의 PID 추출
                String pid = String.valueOf(pythonProcess.pid());

                // 2. Windows 명령어로 트리(/T) 전체를 강제(/F) 종료
                // 이 명령이 실행되면 run_all.py뿐만 아니라 그 자식인 ai_server, predict도 모두 종료됩니다.
                Process killProcess = Runtime.getRuntime().exec("taskkill /F /T /PID " + pid);

                // 3. 종료 명령이 완료될 때까지 최대 5초 대기 (응답 없음 방지)
                killProcess.waitFor();

                System.out.println("🛑 [Auto-Stop] Python AI Server 및 모든 자식 프로세스가 정리되었습니다. (PID: " + pid + ")");

            } catch (Exception e) {
                // 만약 taskkill 명령이 실패할 경우를 대비한 예외 처리
                System.err.println("❌ 강제 종료 중 오류 발생, 일반 종료를 시도합니다: " + e.getMessage());
                pythonProcess.destroyForcibly();
            }
        }
    }
    // ============= AI 서버 프로세스 관리자 끝 =============
}