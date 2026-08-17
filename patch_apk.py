import zipfile, os, shutil, struct, hashlib, zlib

APK_PATH = r"D:\ParentalControl\app-debug.apk"
OUTPUT_PATH = r"D:\ParentalControl\app-debug-patched.apk"
WORK_DIR = r"D:\ParentalControl\tmp_apk"

def patch_dex_file(dex_path):
    with open(dex_path, 'rb') as f:
        data = bytearray(f.read())

    old_full_block = b"\x24http://192.168.1.100:5000/api/report\x00"
    new_full_block = b"\x22http://10.90.4.102:5000/api/report\x00\x00\x00"
    old_url_block  = b"\x19http://192.168.1.100:5000\x00"
    new_url_block  = b"\x17http://10.90.4.102:5000\x00\x00\x00"

    count = 0
    for old_name, old_blk, new_blk in [("/api/report", old_full_block, new_full_block), ("", old_url_block, new_url_block)]:
        idx = data.find(old_blk)
        if idx != -1:
            data[idx:idx+len(old_blk)] = new_blk
            print(f"  Patched URL{old_name} at 0x{idx:x}")
            count += 1

    if count == 0:
        return None

    data[8:12] = b"\x00\x00\x00\x00"
    data[12:32] = b"\x00" * 20
    sha1 = hashlib.sha1(data[32:]).digest()
    data[12:32] = sha1
    adler = zlib.adler32(data[12:]) & 0xFFFFFFFF
    data[8:12] = struct.pack("<I", adler)

    with open(dex_path, 'wb') as f:
        f.write(data)
    return True

def main():
    print("=== APK Patcher ===")
    if os.path.exists(WORK_DIR):
        shutil.rmtree(WORK_DIR)
    os.makedirs(WORK_DIR)

    print(f"Extracting {APK_PATH}...")
    with zipfile.ZipFile(APK_PATH, 'r') as z:
        z.extractall(WORK_DIR)

    nsc = os.path.join(WORK_DIR, "res", "xml", "network_security_config.xml")
    if os.path.exists(nsc):
        with open(nsc, 'rb') as f:
            d = f.read()
        d = d.replace(b'192.168.1.100', b'10.90.4.102')
        with open(nsc, 'wb') as f:
            f.write(d)
        print("Patched network_security_config.xml")

    for fname in sorted(os.listdir(WORK_DIR)):
        if fname.endswith('.dex'):
            fpath = os.path.join(WORK_DIR, fname)
            print(f"\nProcessing {fname}...")
            if patch_dex_file(fpath):
                print(f"  {fname} patched successfully")
            else:
                print(f"  No URL found in {fname}")

    meta = os.path.join(WORK_DIR, "META-INF")
    if os.path.exists(meta):
        shutil.rmtree(meta)
        print("\nRemoved old META-INF signatures")

    print(f"\nRepackaging to {OUTPUT_PATH}...")
    with zipfile.ZipFile(OUTPUT_PATH, 'w', zipfile.ZIP_DEFLATED) as zout:
        for root, dirs, files in os.walk(WORK_DIR):
            for file in files:
                fp = os.path.join(root, file)
                zout.write(fp, os.path.relpath(fp, WORK_DIR))

    apksigner = r"H:\Client_project_Development\Parental_Control_Working\android-sdk\build-tools\34.0.0\apksigner.bat"
    debug_ks = os.path.expanduser("~/.android/debug.keystore")

    if not os.path.exists(debug_ks):
        print(f"\nCreating debug keystore at {debug_ks}...")
        os.system(f'keytool -genkey -v -keystore "{debug_ks}" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"')

    cmd = f'"{apksigner}" sign --ks "{debug_ks}" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey "{OUTPUT_PATH}"'
    print(f"\nSigning...")
    ret = os.system(cmd)
    if ret == 0:
        print("Signing SUCCESS!")
        os.system(f'"{apksigner}" verify --verbose "{OUTPUT_PATH}"')
    else:
        print(f"Signing FAILED (code {ret})")

    shutil.rmtree(WORK_DIR)
    print(f"\nDone! Patched APK: {OUTPUT_PATH}")
    print(f"Size: {os.path.getsize(OUTPUT_PATH) if os.path.exists(OUTPUT_PATH) else 'N/A'} bytes")

if __name__ == '__main__':
    main()
