#!/usr/bin/env bash
# ============================================================
# TVRemoteIME 发布签名 keystore 生成脚本（手动执行一次）
# ============================================================
# 用法：
#   1) 先设置环境变量（当前终端）：
#      export KEYSTORE_PASSWORD='你的keystore密码'
#      export KEY_PASSWORD='你的key密码'        # PKCS12 中 key 与容器共用密码，建议与上面相同
#      export KEY_ALIAS='tvremoteime'           # 可选，默认 tvremoteime
#   2) 运行： bash keystore/generate-keystore.sh
#   3) 生成 keystore/tvremoteime-release.p12 后，把它配到 GitHub Actions Secrets：
#      base64 -w0 keystore/tvremoteime-release.p12 > keystore/base64.txt
#      打开仓库 Settings → Secrets and variables → Actions → New repository secret，
#      新增 4 个 Secret：
#        KEYSTORE_BASE64    = keystore/base64.txt 的内容（一整行 base64）
#        KEYSTORE_PASSWORD  = 上面的 KEYSTORE_PASSWORD
#        KEY_ALIAS          = 上面的 KEY_ALIAS
#        KEY_PASSWORD       = 上面的 KEY_PASSWORD
#   4) push 后 CI 会自动还原 keystore 并签名（debug/release 均为已签名产物，可安装可升级）。
# 注意：keystore 文件、base64.txt 与 keystore.properties 都不要提交到仓库（.gitignore 已忽略）。
set -euo pipefail

OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_P12="${OUT_DIR}/tvremoteime-release.p12"
TMP_KEY="$(mktemp)"
TMP_CERT="$(mktemp)"

: "${KEYSTORE_PASSWORD:?请先 export KEYSTORE_PASSWORD}"
: "${KEY_PASSWORD:?请先 export KEY_PASSWORD}"
KEY_ALIAS="${KEY_ALIAS:-tvremoteime}"

trap 'rm -f "$TMP_KEY" "$TMP_CERT"' EXIT

# 生成自签名证书（RSA 2048 / SHA256 / 30 年有效期）
openssl req -x509 -newkey rsa:2048 -sha256 -days 10950 -nodes \
  -keyout "$TMP_KEY" -out "$TMP_CERT" \
  -subj "/C=CN/O=TVRemoteIME/CN=TVRemoteIME"

# 打包为 PKCS12 keystore（AGP/apksigner 均支持）
openssl pkcs12 -export -inkey "$TMP_KEY" -in "$TMP_CERT" \
  -name "$KEY_ALIAS" -out "$OUT_P12" -passout "pass:${KEYSTORE_PASSWORD}"

echo ""
echo "已生成: ${OUT_P12}"
echo "别名 : ${KEY_ALIAS}"
echo ""
echo "下一步："
echo "  base64 -w0 ${OUT_P12} > ${OUT_DIR}/base64.txt"
echo "  然后将 base64.txt 内容配到 GitHub Secret KEYSTORE_BASE64，密码/别名配到 KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD"
