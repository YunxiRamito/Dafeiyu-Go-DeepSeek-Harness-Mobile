#!/bin/sh
# Fixed Android release helper; previous deployed releases stay immutable.
# Invocation: sh android-release-helper.sh MODE [SOURCE_PARENT APK_SHA256 APK_BYTES]
# MODE defaults to review; other modes are separate explicit administrator actions.
set -eu

mode=${1:-review}
case "$mode" in
  review|verify-source|deploy|verify-deployed|publish) ;;
  *) printf '%s\n' 'Invalid mode.' >&2; exit 2 ;;
esac
[ "$#" -le 4 ] || { printf '%s\n' 'Too many arguments.' >&2; exit 2; }

image='sha256:f3807a00482e26b0d5e87fbaaa5e63a935c09aa2653029e466bb5cbd2bfa2177'
api='developer-center-api-1'
release_root='/home/dafeiyu/developer-center/android-apks'
public_key='/home/dafeiyu/developer-center/tls/android-manifest-public.pem'
version='0.1.31'
code='32'
channel='stable'
package='com.dafeiyu.mobile'
certificate='d4341e66075d1e35909796cb5962b22f8aaed378ca50e057a1b25ed97fe0c64b'
cli_sha='f441c67e933c8471a89946aed6df2da5839613037705d750efe94cb175eeb0c8'
public_sha='dbb1ee3ac20925ed349ea8ed326b389ce463cbfa43b11d2dc22989c4a02a9314'

# Inspect only public identity and state, never production credentials or full Env.
actual_image=$(sudo -n docker inspect --format '{{.Image}}' "$api")
[ "$actual_image" = "$image" ] || { printf '%s\n' 'API image changed; review again.' >&2; exit 2; }
actual_user=$(sudo -n docker inspect --format '{{.Config.User}}' "$api")
[ "$actual_user" = '1654' ] || { printf '%s\n' 'API user changed; review again.' >&2; exit 2; }
[ "$(sudo -n docker inspect --format '{{.State.Running}}' "$api")" = 'true' ]
[ "$(sudo -n docker inspect --format '{{.State.Health.Status}}' "$api")" = 'healthy' ]
[ "$(sudo -n docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/developer-center/android-apks"}}{{.Source}}:{{.RW}}{{end}}{{end}}' "$api")" = "$release_root:false" ]
[ "$(sudo -n docker inspect --format '{{range .Mounts}}{{if eq .Destination "/etc/developer-center/android-manifest-public.pem"}}{{.Source}}:{{.RW}}{{end}}{{end}}' "$api")" = "$public_key:false" ]

python3 - "$release_root" "$public_key" "$public_sha" <<'PY'
import hashlib, pathlib, sys
release, public = (pathlib.Path(value) for value in sys.argv[1:3])
for value in (release, public):
    if not value.is_absolute() or str(value.resolve(strict=True)) != str(value):
        raise SystemExit('Canonical fixed path required.')
    if any(ancestor.is_symlink() for ancestor in (value, *value.parents)):
        raise SystemExit('Release/public-key path contains a link.')
if not release.is_dir() or not public.is_file():
    raise SystemExit('Invalid release/public-key layout.')
if hashlib.sha256(public.read_bytes()).hexdigest() != sys.argv[3]:
    raise SystemExit('Public trust differs from reviewed Android trust.')
PY

actual_cli=$(sudo -n docker exec "$api" sha256sum /app/android-release-cli/AndroidReleaseCli.dll)
[ "${actual_cli%% *}" = "$cli_sha" ] || { printf '%s\n' 'Live CLI changed; review again.' >&2; exit 2; }
actual_deps=$(sudo -n docker exec "$api" sha256sum /app/android-release-cli/AndroidReleaseCli.deps.json)
[ "${actual_deps%% *}" = '66dcb8c36a5203345b0f1aaec9cffdddf4debdb90609a732b66a4fa16457ae7c' ]
actual_runtime=$(sudo -n docker exec "$api" sha256sum /app/android-release-cli/AndroidReleaseCli.runtimeconfig.json)
[ "${actual_runtime%% *}" = '80f8774634e0dbcb3b168df887b8f8dd2dcb990c7b245e6a82f4f2586a59647c' ]
[ "$(sudo -n docker exec "$api" printenv AndroidApk__Path)" = '/var/lib/developer-center/android-apks' ]
[ "$(sudo -n docker exec "$api" printenv AndroidApk__PackageName)" = "$package" ]
[ "$(sudo -n docker exec "$api" printenv AndroidApk__AllowedSigningCertificateSha256__0)" = "$certificate" ]
[ "$(sudo -n docker exec "$api" printenv AndroidApk__ManifestSigningPublicKeyFile)" = '/etc/developer-center/android-manifest-public.pem' ]
[ "$(sudo -n docker exec "$api" printenv AndroidApk__ManifestSignatureAlgorithm)" = 'rsa-sha256' ]
[ "$(sudo -n docker exec "$api" printenv AndroidApk__AllowPreviewDebug)" = 'false' ]

if [ "$mode" = 'review' ]; then
  printf '%s\n' 'REVIEW ONLY: no helper container, upload, deploy or publish has been performed.'
  printf 'image=%s\n' "$image"
  sudo -n docker exec "$api" dotnet --list-runtimes
  stat -c 'release_root mode=%a owner=%u group=%g' "$release_root"
  if sudo -n docker exec "$api" sh -c 'test -r /var/lib/developer-center/android-apks && test -x /var/lib/developer-center/android-apks'; then
    printf '%s\n' 'api_release_root=readable'
  else
    printf '%s\n' 'api_release_root=DENIED; apply only the reviewed group-access fix before deployment.'
  fi
  exit 0
fi

[ "$#" -eq 4 ] || { printf '%s\n' 'Explicit action requires SOURCE_PARENT APK_SHA256 APK_BYTES.' >&2; exit 2; }
source_parent=$2
expected_sha=$3
expected_bytes=$4

# Guard the uploaded snapshot against the parent's frozen release receipt. This
# is a transport/identity check; authentic signature validation is performed by
# the exact AndroidReleaseCli below, on original manifest bytes.
python3 - "$source_parent" "$version" "$code" "$channel" "$package" "$certificate" "$expected_sha" "$expected_bytes" <<'PY'
import hashlib, json, pathlib, re, sys
base, version, code, channel, package, certificate, digest, length = sys.argv[1:]
if not re.fullmatch(r'/home/dafeiyu/developer-center-staging/android-release-[a-z0-9-]{8,80}', base):
    raise SystemExit('Source must be a dedicated Android release staging directory.')
if not re.fullmatch(r'[a-f0-9]{64}', digest) or not re.fullmatch(r'[1-9][0-9]{0,9}', length):
    raise SystemExit('Invalid frozen APK receipt.')
base = pathlib.Path(base)
candidate = base / version
for value in (base, candidate):
    if str(value.resolve(strict=True)) != str(value) or any(x.is_symlink() for x in (value, *value.parents)):
        raise SystemExit('Candidate path contains a link or is not canonical.')
if not candidate.is_dir():
    raise SystemExit('Missing candidate release directory.')
names = {'manifest.json', 'manifest.sig', 'app.apk'}
if {x.name for x in candidate.iterdir()} != names:
    raise SystemExit('Candidate must contain only the three release files.')
for name in names:
    item = candidate / name
    if item.is_symlink() or not item.is_file():
        raise SystemExit('Candidate release contains a link/non-regular file.')
    if item.stat().st_uid != 1000:
        raise SystemExit('Candidate must be owned by the SSH release administrator.')
data = (candidate / 'manifest.json').read_bytes()
if not 0 < len(data) <= 32768:
    raise SystemExit('Manifest exceeds the exact client/server byte limit.')
manifest = json.loads(data.decode('utf-8'))
expected = {'version': version, 'versionCode': int(code), 'channel': channel, 'packageName': package,
            'signingCertificateSha256': certificate, 'sha256': digest, 'byteSize': int(length),
            'buildType': 'release', 'fileName': 'app.apk'}
if any(manifest.get(key) != value for key, value in expected.items()):
    raise SystemExit('Candidate metadata differs from fixed identity/frozen APK receipt.')
apk = candidate / 'app.apk'
if apk.stat().st_size != int(length):
    raise SystemExit('Candidate APK size changed after upload.')
with apk.open('rb') as stream:
    if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
        raise SystemExit('Candidate APK bytes differ from frozen APK receipt.')
PY

if [ "$mode" != 'verify-source' ]; then
  [ "$(stat -c '%a %u:%g' "$release_root")" = '2750 1000:1654' ] || {
    printf '%s\n' 'Release root needs reviewed mode 2750, owner1000/group1654.' >&2; exit 2;
  }
  sudo -n docker exec "$api" sh -c 'test -r /var/lib/developer-center/android-apks && test -x /var/lib/developer-center/android-apks'
fi

case "$mode" in
  verify-source) operation='verify'; helper_root='/source'; root_access='ro' ;;
  deploy)
    [ ! -e "$release_root/$version" ] && [ ! -L "$release_root/$version" ] || {
      printf '%s\n' 'Immutable destination already exists; verify it, never overwrite.' >&2; exit 2;
    }
    operation='deploy'; helper_root='/releases'; root_access='rw' ;;
  verify-deployed) operation='verify'; helper_root='/releases'; root_access='ro' ;;
  publish) operation='publish'; helper_root='/releases'; root_access='rw' ;;
esac

run_cli() {
  # No API/db/secrets/socket mounts, no network, no private key. Diagnostics are
  # disabled so the portable .NET runtime needs no writable root filesystem.
  sudo -n docker run --rm --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges --user 1000:1654 \
    --env DOTNET_EnableDiagnostics=0 --env COMPlus_EnableDiagnostics=0 \
    --mount "type=bind,src=$release_root,dst=/releases,$([ "$root_access" = 'ro' ] && printf 'readonly' || printf 'readonly=false')" \
    --mount "type=bind,src=$source_parent,dst=/source,readonly" \
    --mount "type=bind,src=$public_key,dst=/public.pem,readonly" \
    --entrypoint /bin/sh "$image" -c \
    'set -eu; umask 027; printf "%s  %s\n" "f441c67e933c8471a89946aed6df2da5839613037705d750efe94cb175eeb0c8" "/app/android-release-cli/AndroidReleaseCli.dll" "66dcb8c36a5203345b0f1aaec9cffdddf4debdb90609a732b66a4fa16457ae7c" "/app/android-release-cli/AndroidReleaseCli.deps.json" "80f8774634e0dbcb3b168df887b8f8dd2dcb990c7b245e6a82f4f2586a59647c" "/app/android-release-cli/AndroidReleaseCli.runtimeconfig.json" | sha256sum -c >/dev/null; exec dotnet /app/android-release-cli/AndroidReleaseCli.dll "$@"' \
    release-cli "$@"
}

verify_live_api() {
  # The live API uses its existing read-only mount and actual service uid. This
  # verification must succeed BEFORE publish can mutate any current pointer.
  api_result=$(sudo -n docker exec --env DOTNET_EnableDiagnostics=0 --env COMPlus_EnableDiagnostics=0 \
    "$api" dotnet /app/android-release-cli/AndroidReleaseCli.dll \
    verify --root /var/lib/developer-center/android-apks --package "$package" --certificate "$certificate" \
    --public-key /etc/developer-center/android-manifest-public.pem --algorithm rsa-sha256 \
    --version "$version" --allow-preview-debug false)
  python3 - "$api_result" "$version" "$code" "$channel" "$expected_sha" <<'PY'
import json, sys
value = json.loads(sys.argv[1])
expected = {'operation': 'verify', 'Version': sys.argv[2], 'VersionCode': int(sys.argv[3]),
            'Channel': sys.argv[4], 'Sha256': sys.argv[5]}
if value != expected:
    raise SystemExit('Live API-user release verification failed.')
print('PASS real API user verified original signed release and APK bytes.')
PY
}

if [ "$mode" = 'publish' ]; then verify_live_api; fi

set -- "$operation" --root "$helper_root" --package "$package" --certificate "$certificate" \
  --public-key /public.pem --algorithm rsa-sha256 --version "$version" --allow-preview-debug false
if [ "$mode" = 'deploy' ]; then set -- "$@" --source "/source/$version"; fi
if [ "$mode" = 'publish' ]; then set -- "$@" --channel "$channel"; fi
result=$(run_cli "$@")
python3 - "$result" "$operation" "$version" "$code" "$channel" "$expected_sha" <<'PY'
import json, sys
value = json.loads(sys.argv[1])
expected = {'operation': sys.argv[2], 'Version': sys.argv[3], 'VersionCode': int(sys.argv[4]),
            'Channel': sys.argv[5], 'Sha256': sys.argv[6]}
if value != expected:
    raise SystemExit('CLI receipt differs from the exact requested release.')
print(json.dumps(value, separators=(',', ':')))
PY

if [ "$mode" = 'verify-deployed' ]; then verify_live_api; fi
