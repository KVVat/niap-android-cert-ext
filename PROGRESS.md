# NIAP Cert Ext — 進捗メモ

最終更新: 2026-05-21

## 完了済み

### 署名オラクル設計への移行
- `INiapCertManager.aidl` から `verifyMtls` を削除、`sign(alias, digestBytes)` を追加
- Service は鍵署名のみ担当、HTTP/TLS 通信は cert-lib 側（呼び出し元プロセス）で完結
- `KeyStoreEngine.sign()` を追加し、Service 内の KeyStore アクセスを一元化
- `NiapCertService.sign()` が `KeyStoreEngine.sign()` に委譲

### cert-lib 側の実装
- `RemotePrivateKey` / `RemoteSignatureSpi` / `RemoteSigningProvider`: Conscrypt の `NONEwithECDSA` を AIDL `sign()` に橋渡しするカスタム JCA Provider
- `RemoteSigningContext`: ThreadLocal で TLS ハンドシェイク中の署名関数を保持
- `MtlsKeyProvider` インターフェース + `ServiceMtlsKeyProvider`（実装済み）+ `KeyChainMtlsKeyProvider`（スタブのみ）
- `NiapCertManager.verifyMtls()`: cert-lib 内で OkHttp mTLS を実行

### テスト
- `testEnrollAndVerifyMtls`: EST enrollment → mTLS 検証（HTTP 200）の E2E テスト PASS
- `ManagerActivity`: `action=verifyMtls` 対応、`onNewIntent` + `handleIntent()` に整理

---

## 次回再開ポイント

### 1. Service 内 KeyStore 設計の見直し（優先）

現状の問題意識:
- `KeyStoreEngine` は cert-manager モジュール（Service プロセス）に存在し、AndroidKeyStore を直接操作
- **cert-lib から直接 KeyStore にアクセスしても、UID が違うため鍵は見えない** → 署名オラクルパターンが必須
- しかし `KeyStoreEngine` のスコープ・責務がまだ曖昧。鍵生成（`CsrEngine`）・証明書格納・署名が散在気味

見直し案:
- `KeyStoreEngine` に鍵生成以外の KeyStore 操作（getCertificateData, storeCertificate, deleteCertificate, sign）を集約
- `CsrEngine` は CSR 生成と鍵ペア生成に専念、KeyStore 書き込みは `KeyStoreEngine` 経由に統一
- `NiapCertOrchestrator` → `KeyStoreEngine` 経由で操作する形に整理

### 2. 証明書格納場所の設計（最重要論点）

EST enrollment で戻ってきた証明書を **どこに格納するか** が、呼び出し元の自由度を決める。

| 格納先 | 署名 | cert-lib からのアクセス | 他用途（WebView等） |
|--------|------|------------------------|---------------------|
| Service の AndroidKeyStore（現状） | AIDL 経由 (sign oracle) | AIDL getCertificateData() | 不可（AIDL 必須） |
| システム KeyChain | KeyChain.getPrivateKey()（別プロセス不要） | KeyChain.getCertificateChain() 直接 | 可能 |

**現状の問題意識（ユーザーより）:**
- Service は EST enrollment（通信）を行うのは OK
- ただし「通信で戻ってきた証明書の格納を呼び出し側プロセスがやらないと破綻する」可能性がある
- → Service の KeyStore に閉じると、呼び出し元は常に AIDL を通らなければならない
- → KeyChain であれば証明書はシステム管理、呼び出し元が直接アクセス可能

**設計上の選択肢:**
1. **現状維持**: Service KeyStore + AIDL sign oracle。シンプルだが呼び出し元の柔軟性が低い
2. **KeyChain バックエンド追加**: `KeyChainMtlsKeyProvider` を実装。cert-lib から直接アクセス可能。ただし enrollment フローが変わる
3. **ハイブリッド**: enrollment 完了後に証明書を呼び出し元へ返し、呼び出し元が KeyChain に格納する形

→ **次回、この格納責務の所在を決める設計議論から開始する**

### 3. AIDL インターフェース再設計

上記格納設計を踏まえて AIDL の API を見直す。

現状:
```
requestCertificate(...)      // enrollment をトリガー
getCertificateStatus(alias)
getCertificateData(alias)    // 格納先が変わると不要になる可能性
getErrorMessage(alias)
revokeCertificate(alias)
sign(alias, digestBytes)     // 署名オラクル（今回追加）
```

- `verifyMtls` という名前は変えてよい（ユーザー確認済み）

### 3. testbed-core の配置

- 現在: `/Users/kwatanabe/work-repo/testbed-core/` （別ディレクトリ）
- 理想: このプロジェクトと同じディレクトリ階層、または実行ファイル形式
- agent-test jar のデプロイ先が `/Users/kwatanabe/work-repo/testbed-core/composeApp/plugins/agent-test/` にハードコードされている（`agent-test/build.gradle.kts` の `destinationDirectory`）
- プロジェクト移動後に `build.gradle.kts` のパスを更新すること

---

## 技術的ポイント（忘れないように）

| 事項 | 内容 |
|------|------|
| DIGEST_NONE | Conscrypt は TLS CertificateVerify に `NONEwithECDSA` を使う。`KeyGenParameterSpec` に `DIGEST_NONE` が必須 |
| AndroidKeyStore スコープ | UID ごとに分離。cert-lib（呼び出し元プロセス）から cert-manager の鍵は見えない |
| NONEwithECDSA ルーティング | `RemoteSigningProvider.supportsParameter(key: Any?) = key is RemotePrivateKey` で他 Provider へのフォールスルーを防ぐ |
| TLS 1.2/1.3 両対応 | DIGEST_NONE があれば ConnectionSpec 制限不要 |
| EST CA PEM | ManagerActivity が `http://localhost:8080/cacert.pem` から動的取得（adb reverse tcp:8080 前提） |
