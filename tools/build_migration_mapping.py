"""Link discovered source controllers to reviewed migration work; never infer parity."""
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java"
TESTS = ROOT / "src/test/java"
MAPPING = {}


def mapped(sources, targets, tests, gap):
    for source in sources.split(","):
        MAPPING[source] = (targets, tests, gap)


mapped("AuthAplyController,AuthController", "AuthorityWorkflowController;AuthorizationCatalog;AuthorityRequestService", "AuthorityRequestServiceTest;AuthorityWorkflowSecurityTest", "원본 트리·조회조건·권한 이력 모델과 전체 화면 계약 미완료")
mapped("MenuController,PgmController", "AdminController;AuthorizationCatalog", "CommonCodeHierarchyTest;ProgramAuthorizationServiceTest", "원본 전체 컬럼·양방향 조회·출력 계약 미완료")
mapped("UsrMngController", "DirectoryController;AuthorityWorkflowController;AuthorityRevocationController", "CommonWorkIntegrationTest;AuthorityWorkflowSecurityTest", "SSO 조직·사업소별 범위·전체 사용자 필드·이력 처리 미완료")
mapped("MyInfoController", "DirectoryController;LocalAccountService", "CommonWorkIntegrationTest", "원본 계정 체계·SSO 비밀번호 정책과의 대응 미완료")
mapped("UsrRgnController,JobChrgerMngController,OfficeMngController", "DirectoryController;DirectoryService", "CommonWorkIntegrationTest", "원본 전체 필드·특화 조회·참조 정책 미완료")
mapped("UsrMntrngController", "SessionMonitorController;SessionMonitorService", "CommonWorkIntegrationTest", "현재 서버 세션만 구현; 원본 접속 모니터링 전체 항목 미완료")
mapped("CmCdMngController", "CodeManagementController;CommonCodeService", "CommonCodeServiceTest;CommonCodeHierarchyTest", "표시 플래그별 조회·원본 삭제 및 출력 계약 추가 확인 필요")
mapped("CdValMngController", "ReferenceDataController;ReferenceDataService", "CommonWorkIntegrationTest", "코드값 기본 CRUD 구현; 원본 조회조건·중복/삭제 정책별 동등성 검증 미완료")
mapped("PrintCnMngController", "PrintTextController;PrintTextService", "CommonWorkIntegrationTest", "묶음·순번·100자 문구·CM013/CM040 관계 구현; 원본 사업소 범위·보고서 적용 계약 미완료")
mapped("HpGuideMngController", "HelpGuideController;HelpGuideService", "CommonWorkIntegrationTest", "메뉴별 본문·첨부·전체 버전·열람 구현; 원본 도움말 구분·통계·팝업 계약 미완료")
mapped("BbsMngController", "BoardController;BoardService;ContentService", "CommonWorkIntegrationTest", "게시판 구분·사용/공지/답변/첨부 정책·조회수 구현; 원본 자동 번호·팝업·메인 롤링·전체 컬럼 미완료")
mapped("BankCdMngController,HolidayMngController,InsttCdMngController,PrmtMngController,BasicChrMsgMngController,WorkDayMngController", "ReferenceDataController;ReferenceDataService", "CommonWorkIntegrationTest", "원본 추가 컬럼·조회조건·계산/연간 생성·출력 미완료; 테스트는 은행/매개변수 관계 중심")
mapped("BbsDocMngController,BbsFaqMngController,BbsNtcMngController,BbsQnaMngController,BbsUtztnTrmsController,BbsUtztnTrmsAgreController", "ContentController;ContentService", "CommonWorkIntegrationTest", "게시판별 전체 필드·조회수·팝업·시스템/유형별 약관 등 미완료")
mapped("UsrSchdulMngController", "PersonalScheduleController;PersonalScheduleService", "CommonWorkIntegrationTest", "원본 일정 코드·달력 표현과 전체 계약 미완료")
mapped("JobAlrmController", "InboxController;InboxService", "AuthorityWorkflowSecurityTest", "신규 권한 심사/회수 알림만 구현; 원본 업무 알림 집계 미완료")
mapped("TaskMngController,IssueMngController", "OperationWorkController;OperationWorkService", "CommonWorkIntegrationTest", "원본 상태 코드·특화 검색·출력·오류 등록 팝업·전용 업무 권한 미완료")
mapped("FileUploadController", "ContentController;AttachmentRepository;AttachmentUse", "CommonWorkIntegrationTest;LocalPersistenceTest", "게시판/운영 첨부 구현; 원본 업무별 파일 연결 및 외부 저장 계약 미완료")


def resolve_names(folder, names):
    result = []
    for name in names.split(";"):
        paths = list(folder.rglob(name + ".java"))
        if len(paths) != 1:
            raise ValueError(f"Missing or ambiguous local evidence: {name}")
        result.append(paths[0].relative_to(ROOT).as_posix())
    return ";".join(result)


def main():
    with (ROOT / "migration/inventory/files.csv").open(encoding="utf-8-sig", newline="") as stream:
        sources = [row["source"] for row in csv.DictReader(stream) if row["source"].endswith("Controller.java")]
    found = {Path(source).stem for source in sources}
    missing = set(MAPPING) - found
    if missing:
        raise ValueError(f"Mapped source is absent from inventory: {sorted(missing)}")
    rows = []
    for source in sorted(sources):
        entry = MAPPING.get(Path(source).stem)
        row = {"source_controller": source, "status": "NOT_MAPPED", "target_sources": "", "tests": "", "remaining": "대응 구현·동등성 검증 필요"}
        if entry:
            targets, tests, gap = entry
            row.update(status="PARTIAL", target_sources=resolve_names(JAVA, targets), tests=resolve_names(TESTS, tests), remaining=gap)
        rows.append(row)
    with (ROOT / "migration/function-mapping.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    summary = {"source_controllers": len(rows), "partial": sum(r["status"] == "PARTIAL" for r in rows),
               "not_mapped": sum(r["status"] == "NOT_MAPPED" for r in rows), "parity_verified": 0,
               "note": "Controller mapping only. Counts are not migrated function counts; tests do not establish original parity."}
    (ROOT / "migration/mapping-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
