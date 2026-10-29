# AMretro Windows dependency installer. Downloads unmodified official packages.
# Windows PowerShell 5.1+. No user games, save states, BIOS, or existing valid
# cores are deleted or overwritten. Failure is fatal, never a false success.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [Parameter(Mandatory = $true)][string]$Extractor,
    [Parameter(Mandatory = $true)][string]$CoreList,
    [Parameter(Mandatory = $true)][string]$AssetManifest,
    [Parameter(Mandatory = $true)][string]$Launcher,
    [string]$CacheDir = '',
    [switch]$TestMode
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$runtimeUrl = 'https://buildbot.libretro.com/stable/1.22.2/windows/x86_64/RetroArch.7z'
$coreUrl = 'https://buildbot.libretro.com/stable/1.22.2/windows/x86_64/RetroArch_cores.7z'
$pspRevision = 'c1581e6c3873331b93868bccd2f53f34a2316f89'
$dolphinRevision = '227c4e05158e7c3cc41cfc751ba6b5df92193d26'
$pspUrl = 'https://codeload.github.com/hrydgard/ppsspp/zip/' + $pspRevision
$dolphinUrl = 'https://codeload.github.com/libretro/dolphin/zip/' + $dolphinRevision
$runtimeHash = 'b2139b1d0f9d4526dc6b5ce23cbb3efdc766096fa6f2c3df016818b486ac6372'
$coreHash = '86b871e11b9b4772ac644b40a38f2c8e9449da1f355eae7da08aa061148547b0'
$pspHash = '4dfa0b1f759161381aebaa1f15f80cf94acab8bbe4a9c47728a55b4aaef141f9'
$dolphinHash = '3e281c2bbd902ba3c527f35dea1ede809394b471be1658b102b7c3b41c0d44d2'
$extractorHash = '15d4c788c148e3677e2fc1c4a01f191fb6669eba4f6acf8a465f0f1a6f1e1260'
$isWindowsSystem = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
if (-not $isWindowsSystem -and -not $TestMode) { throw 'Windows 10 or later is required.' }
if ([string]::IsNullOrWhiteSpace($env:APPDATA)) { throw 'Windows AppData is unavailable.' }
if (-not (Test-Path -LiteralPath $Extractor -PathType Leaf)) { throw 'Extractor missing.' }
if ($isWindowsSystem -and (Get-FileHash -LiteralPath $Extractor -Algorithm SHA256).Hash.ToLowerInvariant() -ne $extractorHash) { throw 'Extractor integrity check failed.' }
if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) { throw 'Launcher missing.' }
if (-not (Test-Path -LiteralPath $CoreList -PathType Leaf)) { throw 'Core list missing.' }
if (-not (Test-Path -LiteralPath $AssetManifest -PathType Leaf)) { throw 'Support-file manifest missing.' }
$assets = Get-Content -LiteralPath $AssetManifest -Raw -Encoding UTF8 | ConvertFrom-Json
if (@($assets.ppsspp).Count -ne 167 -or @($assets.dolphin).Count -ne 2498) { throw 'Support-file manifest is incomplete.' }
foreach ($relative in @($assets.ppsspp) + @($assets.dolphin)) {
    if ([string]::IsNullOrWhiteSpace($relative) -or $relative.StartsWith('/') -or $relative -match '(^|/)\.\.(/|$)' -or $relative -match '[<>:"\\|?*]') { throw 'Unsafe support-file name.' }
}
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
if (-not [IO.Path]::IsPathRooted($InstallDir)) { throw 'Install path must be absolute.' }
$names = @(Get-Content -LiteralPath $CoreList -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($names.Count -eq 0 -or $names.Count -gt 100 -or (@($names | Select-Object -Unique)).Count -ne $names.Count) { throw 'Invalid core list.' }
foreach ($item in $names) {
    if ($item -cnotmatch '^RetroArch-Win64/cores/[a-z0-9_-]+_libretro\.dll$') { throw 'Unsafe core name.' }
}

# Read/write settings through the Windows system SQLite; backup is the SQLite
# online backup API, including WAL transactions. Never open an absent DB.
$sqliteLibrary = if ($isWindowsSystem) { 'winsqlite3.dll' } else { 'libsqlite3.so.0' }
$sqliteSource = @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class AMretroSetupSqlite {
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_open_v2(byte[] p, out IntPtr d, int f, IntPtr v);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_close(IntPtr d);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_busy_timeout(IntPtr d,int t);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_prepare_v2(IntPtr d,byte[] s,int n,out IntPtr q,IntPtr tail);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_step(IntPtr q);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_finalize(IntPtr q);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern IntPtr sqlite3_column_text(IntPtr q,int n);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_column_bytes(IntPtr q,int n);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_bind_text(IntPtr q,int i,byte[] b,int n,IntPtr destroy);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_changes(IntPtr d);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_exec(IntPtr d,byte[] s,IntPtr cb,IntPtr ctx,IntPtr err);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern IntPtr sqlite3_backup_init(IntPtr dst,byte[] dn,IntPtr src,byte[] sn);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_backup_step(IntPtr b,int n);
  [DllImport("$sqliteLibrary", CallingConvention=CallingConvention.Cdecl)] private static extern int sqlite3_backup_finish(IntPtr b);
  private static byte[] Z(string s) { return Encoding.UTF8.GetBytes(s + "\0"); }
  private static IntPtr Open(string path,int flags) {
    IntPtr db; int rc=sqlite3_open_v2(Z(path),out db,flags,IntPtr.Zero);
    if (rc!=0) { if(db!=IntPtr.Zero) sqlite3_close(db); throw new InvalidOperationException("Cannot open AMretro settings (SQLite " + rc + ")."); }
    sqlite3_busy_timeout(db,10000); return db;
  }
  public static string Read(string path,string key) {
    if(key!="cores_dir" && key!="retroarch_path" && key!="system_dir") throw new InvalidOperationException("Invalid key.");
    IntPtr db=Open(path,1), st=IntPtr.Zero;
    try {
      string sql=key=="cores_dir" ? "SELECT value FROM settings WHERE key='cores_dir'" : key=="system_dir" ? "SELECT value FROM settings WHERE key='system_dir'" : "SELECT value FROM settings WHERE key='retroarch_path'";
      if(sqlite3_prepare_v2(db,Z(sql),-1,out st,IntPtr.Zero)!=0) throw new InvalidOperationException("Cannot read AMretro settings.");
      int rc=sqlite3_step(st); if(rc==101) return null; if(rc!=100) throw new InvalidOperationException("Cannot read AMretro setting (SQLite " + rc + ").");
      int n=sqlite3_column_bytes(st,0); IntPtr p=sqlite3_column_text(st,0); if(p==IntPtr.Zero) return null;
      byte[] b=new byte[n]; Marshal.Copy(p,b,0,n); return Encoding.UTF8.GetString(b);
    } finally { if(st!=IntPtr.Zero) sqlite3_finalize(st); sqlite3_close(db); }
  }
  public static void Repair(string path,string oldValue,string newValue,string backupPath) {
    IntPtr db=Open(path,2), dest=IntPtr.Zero, st=IntPtr.Zero; bool transaction=false;
    try {
      dest=Open(backupPath,2|4);
      IntPtr b=sqlite3_backup_init(dest,Z("main"),db,Z("main"));
      if(b==IntPtr.Zero) throw new InvalidOperationException("Could not start settings backup.");
      int step=sqlite3_backup_step(b,-1), finish=sqlite3_backup_finish(b);
      if(step!=101 || finish!=0) throw new InvalidOperationException("Could not complete settings backup.");
      sqlite3_close(dest); dest=IntPtr.Zero;
      if(sqlite3_exec(db,Z("BEGIN IMMEDIATE"),IntPtr.Zero,IntPtr.Zero,IntPtr.Zero)!=0) throw new InvalidOperationException("Settings are busy.");
      transaction=true;
      if(sqlite3_prepare_v2(db,Z("UPDATE settings SET value=?1 WHERE key='retroarch_path' AND value=?2"),-1,out st,IntPtr.Zero)!=0) throw new InvalidOperationException("Cannot prepare settings repair.");
      byte[] a=Encoding.UTF8.GetBytes(newValue), c=Encoding.UTF8.GetBytes(oldValue);
      if(sqlite3_bind_text(st,1,a,a.Length,new IntPtr(-1))!=0 || sqlite3_bind_text(st,2,c,c.Length,new IntPtr(-1))!=0 || sqlite3_step(st)!=101 || sqlite3_changes(db)!=1) throw new InvalidOperationException("Settings changed; no repair was applied.");
      sqlite3_finalize(st); st=IntPtr.Zero;
      if(sqlite3_exec(db,Z("COMMIT"),IntPtr.Zero,IntPtr.Zero,IntPtr.Zero)!=0) throw new InvalidOperationException("Settings repair could not be committed.");
      transaction=false;
    } finally {
      if(st!=IntPtr.Zero) sqlite3_finalize(st);
      if(transaction) sqlite3_exec(db,Z("ROLLBACK"),IntPtr.Zero,IntPtr.Zero,IntPtr.Zero);
      if(dest!=IntPtr.Zero) sqlite3_close(dest);
      sqlite3_close(db);
    }
  }
}
"@
Add-Type -TypeDefinition $sqliteSource

function Test-Pe64([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $s = $null; $b = $null
    try {
        $s = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        if ($s.Length -lt 256) { return $false }
        $b = New-Object IO.BinaryReader($s)
        if ($b.ReadUInt16() -ne 0x5a4d) { return $false }
        $s.Position = 0x3c; $offset = $b.ReadUInt32()
        if ($offset -gt ($s.Length - 6)) { return $false }
        $s.Position = $offset
        return ($b.ReadUInt32() -eq 0x00004550 -and $b.ReadUInt16() -eq 0x8664)
    } catch { return $false } finally { if ($b) { $b.Dispose() } elseif ($s) { $s.Dispose() } }
}
function Confirm-Hash([string]$Path,[string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Dependency archive is missing.' }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) { throw 'Dependency archive checksum mismatch; installation stopped.' }
}
function Get-Archive([string]$Name,[string]$Url,[string]$Hash,[long]$ExpectedSize,[string]$TempDir) {
    if (-not [string]::IsNullOrWhiteSpace($CacheDir)) {
        $cached = Join-Path $CacheDir $Name; Confirm-Hash $cached $Hash; return $cached
    }
    $target = Join-Path $TempDir $Name
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $req = [Net.HttpWebRequest]::Create($Url)
    $req.Timeout = 120000; $req.ReadWriteTimeout = 120000; $req.AllowAutoRedirect = $true
    $resp = $null; $source = $null; $dest = $null
    try {
        $resp = $req.GetResponse()
        if ($resp.ContentLength -gt 0 -and $resp.ContentLength -ne $ExpectedSize) { throw 'Unexpected download size.' }
        $source = $resp.GetResponseStream(); $dest = [IO.File]::Open($target,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $buffer = New-Object byte[] (1024 * 1024); [long]$total = 0
        while (($n = $source.Read($buffer,0,$buffer.Length)) -gt 0) {
            $total += $n; if ($total -gt $ExpectedSize) { throw 'Download exceeds expected size.' }
            $dest.Write($buffer,0,$n)
            Write-Progress -Activity 'AMretro: downloading required components' -Status $Name -PercentComplete ([int](100 * $total / $ExpectedSize))
        }
        if ($total -ne $ExpectedSize) { throw 'Incomplete download.' }
    } finally {
        if ($dest) { $dest.Dispose() }; if ($source) { $source.Dispose() }; if ($resp) { $resp.Dispose() }
        Write-Progress -Activity 'AMretro: downloading required components' -Completed
    }
    Confirm-Hash $target $Hash; return $target
}
function Test-TreeComplete([string]$Root,[object[]]$Files) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return $false }
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    foreach ($relative in $Files) {
        $target = [IO.Path]::GetFullPath((Join-Path $rootPath $relative))
        if (-not $target.StartsWith($rootPath + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe manifest path.' }
        if (-not [IO.File]::Exists($target)) { return $false }
    }
    return $true
}
function Copy-TreeMissing([string]$Source,[string]$Dest) {
    $sourceRoot = [IO.Path]::GetFullPath($Source).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $destRoot = [IO.Path]::GetFullPath($Dest).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) { throw 'Support assets missing in source archive.' }
    [IO.Directory]::CreateDirectory($destRoot) | Out-Null
    if (([IO.File]::GetAttributes($destRoot) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'The support-file destination is a junction.' }
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File) {
        $relative = $file.FullName.Substring($sourceRoot.Length).TrimStart([IO.Path]::DirectorySeparatorChar)
        $target = [IO.Path]::GetFullPath((Join-Path $destRoot $relative))
        if (-not $target.StartsWith($destRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe support-file path.' }
        $parent = [IO.Path]::GetDirectoryName($target)
        # Check each existing component before creating the next one: a junction
        # must never redirect installer writes into unrelated user folders.
        $suffix = $parent.Substring($destRoot.Length).TrimStart([IO.Path]::DirectorySeparatorChar)
        $cursor = $destRoot
        if ($suffix.Length -gt 0) {
            foreach ($part in $suffix.Split([IO.Path]::DirectorySeparatorChar)) {
                $cursor = Join-Path $cursor $part
                if (Test-Path -LiteralPath $cursor) {
                    if (([IO.File]::GetAttributes($cursor) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'A support-file directory is a junction; no files were overwritten.' }
                } else { [IO.Directory]::CreateDirectory($cursor) | Out-Null }
            }
        }
        if (-not [IO.File]::Exists($target)) { [IO.File]::Copy($file.FullName,$target,$false) }
    }
}
function Copy-CoreSafely([string]$Source,[string]$Dest) {
    if (-not (Test-Pe64 $Source)) { throw 'Downloaded core is not a valid Windows 64-bit DLL.' }
    if (Test-Pe64 $Dest) { return }
    $dir = [IO.Path]::GetDirectoryName($Dest)
    $temp = Join-Path $dir ('.amretro-core-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::Copy($Source,$temp,$false)
        if ([IO.File]::Exists($Dest)) {
            $backup = $Dest + '.amretro-backup-' + [Guid]::NewGuid().ToString('N')
            [IO.File]::Replace($temp,$Dest,$backup)
        } else { [IO.File]::Move($temp,$Dest) }
    } finally { if ([IO.File]::Exists($temp)) { [IO.File]::Delete($temp) } }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('AMretroSetup-' + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $InstallDir ('.amretro-runtime-stage-' + [Guid]::NewGuid().ToString('N'))
$runtime = Join-Path $InstallDir 'AMretroRuntime'
$runtimeExe = Join-Path $runtime 'retroarch.exe'
$wrapper = Join-Path $InstallDir 'retroarch.exe'
$db = Join-Path (Join-Path $env:APPDATA 'com.retrohub.launcher') 'retro-hub.sqlite3'
$cores = Join-Path (Join-Path $env:APPDATA 'RetroArch') 'cores'
$system = Join-Path (Join-Path $env:APPDATA 'RetroArch') 'system'
$retroPath = $null
try {
    if ($isWindowsSystem -and (Get-Process -Name 'AMretro' -ErrorAction SilentlyContinue)) { throw 'Close AMretro before running its installer.' }
    if (Test-Path -LiteralPath $db -PathType Leaf) {
        $configured = [AMretroSetupSqlite]::Read($db,'cores_dir')
        $configuredSystem = [AMretroSetupSqlite]::Read($db,'system_dir')
        $retroPath = [AMretroSetupSqlite]::Read($db,'retroarch_path')
        if (-not [string]::IsNullOrWhiteSpace($configured)) { $cores = $configured }
        if (-not [string]::IsNullOrWhiteSpace($configuredSystem)) { $system = $configuredSystem }
    }
    if (-not [IO.Path]::IsPathRooted($cores) -or -not [IO.Path]::IsPathRooted($system)) { throw 'Configured cores or system directory is not absolute.' }
    $cores = [IO.Path]::GetFullPath($cores)
    $system = [IO.Path]::GetFullPath($system)
    if ($isWindowsSystem) {
        $windowsPath = [IO.Path]::GetFullPath($env:WINDIR).TrimEnd('\')
        foreach ($destination in @($cores,$system)) {
            if ($destination -ieq [IO.Path]::GetPathRoot($destination) -or $destination -ieq $windowsPath -or $destination.StartsWith($windowsPath + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe cores or system directory.' }
        }
    }
    if (-not (Test-Pe64 $Launcher)) { throw 'The included launcher is not a valid Windows 64-bit executable.' }
    [IO.Directory]::CreateDirectory($InstallDir) | Out-Null
    [IO.Directory]::CreateDirectory($work) | Out-Null
    [IO.Directory]::CreateDirectory($cores) | Out-Null
    [IO.Directory]::CreateDirectory($system) | Out-Null
    if (([IO.File]::GetAttributes($system) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'The selected system directory is a junction; BIOS files were not modified.' }
    $needsRuntime = -not ((Test-Pe64 $runtimeExe) -and (Test-Path -LiteralPath (Join-Path $runtime 'assets') -PathType Container) -and (Test-Path -LiteralPath (Join-Path $runtime 'info') -PathType Container) -and (Test-Path -LiteralPath (Join-Path $runtime 'autoconfig') -PathType Container))
    if ($needsRuntime) {
        if (Test-Path -LiteralPath $runtime) { throw 'Existing runtime is incomplete; it was preserved without overwriting.' }
        Write-Host 'Downloading RetroArch runtime (about 194 MiB)...'
        $archive = Get-Archive 'RetroArch-1.22.2-win64.7z' $runtimeUrl $runtimeHash 202509078 $work
        [IO.Directory]::CreateDirectory($stage) | Out-Null
        & $Extractor x $archive ("-o" + $stage) '-y' '-bso0' '-bsp0' '-bse1'
        if ($LASTEXITCODE -ne 0) { throw 'RetroArch extraction failed.' }
        $newRuntime = Join-Path $stage 'RetroArch-Win64'
        if (-not (Test-Pe64 (Join-Path $newRuntime 'retroarch.exe')) -or -not (Test-Path -LiteralPath (Join-Path $newRuntime 'assets') -PathType Container)) { throw 'Extracted runtime is incomplete.' }
        if (Test-Path -LiteralPath $runtime) { throw 'Runtime path changed during setup; nothing was overwritten.' }
        [IO.Directory]::Move($newRuntime,$runtime)
    } else { Write-Host 'Existing AMretro runtime preserved.' }
    $missing = @($names | Where-Object { -not (Test-Pe64 (Join-Path $cores ([IO.Path]::GetFileName($_)))) })
    if ($missing.Count -gt 0) {
        Write-Host ('Downloading selected emulator cores (about 220 MiB; missing: ' + $missing.Count + ')...')
        $archive = Get-Archive 'RetroArch-1.22.2-win64-cores.7z' $coreUrl $coreHash 229761684 $work
        $coreStage = Join-Path $work 'extracted-cores'; [IO.Directory]::CreateDirectory($coreStage) | Out-Null
        & $Extractor e $archive ('@' + $CoreList) ("-o" + $coreStage) '-y' '-bso0' '-bsp0' '-bse1'
        if ($LASTEXITCODE -ne 0) { throw 'Core extraction failed.' }
        foreach ($item in $names) {
            $name = [IO.Path]::GetFileName($item)
            Copy-CoreSafely (Join-Path $coreStage $name) (Join-Path $cores $name)
        }
    } else { Write-Host 'Existing cores preserved.' }
    foreach ($item in $names) { if (-not (Test-Pe64 (Join-Path $cores ([IO.Path]::GetFileName($item))))) { throw 'A required core is still missing.' } }
    $pspDest = Join-Path $system 'PPSSPP'
    if (-not (Test-TreeComplete $pspDest @($assets.ppsspp))) {
        Write-Host 'Downloading pinned PPSSPP support files (about 38 MiB)...'
        $archive = Get-Archive 'ppsspp-src-c1581e6.zip' $pspUrl $pspHash 39707996 $work
        $pspStage = Join-Path $work 'psp-assets'; [IO.Directory]::CreateDirectory($pspStage) | Out-Null
        $prefix = 'ppsspp-' + $pspRevision
        & $Extractor x $archive ($prefix + '/assets/*') ($prefix + '/LICENSE.TXT') ("-o" + $pspStage) '-y' '-bso0' '-bsp0' '-bse1'
        if ($LASTEXITCODE -ne 0) { throw 'PPSSPP support-file extraction failed.' }
        $source = Join-Path (Join-Path $pspStage $prefix) 'assets'
        Copy-TreeMissing $source $pspDest
        $licenseSource = Join-Path (Join-Path $pspStage $prefix) 'LICENSE.TXT'
        $licenseDest = Join-Path $pspDest 'LICENSE.TXT'
        if (-not [IO.File]::Exists($licenseDest)) { [IO.File]::Copy($licenseSource,$licenseDest,$false) }
        if (-not (Test-TreeComplete $pspDest @($assets.ppsspp))) { throw 'PPSSPP support files are incomplete.' }
    }
    $dolphinBase = Join-Path $system 'dolphin-emu'
    $dolphinDest = Join-Path $dolphinBase 'Sys'
    if (-not (Test-TreeComplete $dolphinDest @($assets.dolphin))) {
        Write-Host 'Downloading pinned Dolphin support files (about 21 MiB)...'
        $archive = Get-Archive 'dolphin-src-227c4e0.zip' $dolphinUrl $dolphinHash 21551037 $work
        $dolphinStage = Join-Path $work 'dolphin-assets'; [IO.Directory]::CreateDirectory($dolphinStage) | Out-Null
        $prefix = 'dolphin-' + $dolphinRevision
        & $Extractor x $archive ($prefix + '/Data/Sys/*') ($prefix + '/COPYING') ($prefix + '/LICENSES/*') ("-o" + $dolphinStage) '-y' '-bso0' '-bsp0' '-bse1'
        if ($LASTEXITCODE -ne 0) { throw 'Dolphin support-file extraction failed.' }
        $source = Join-Path (Join-Path (Join-Path $dolphinStage $prefix) 'Data') 'Sys'
        Copy-TreeMissing $source $dolphinDest
        Copy-TreeMissing (Join-Path (Join-Path $dolphinStage $prefix) 'LICENSES') (Join-Path $dolphinBase 'LICENSES')
        $licenseSource = Join-Path (Join-Path $dolphinStage $prefix) 'COPYING'
        $licenseDest = Join-Path $dolphinBase 'COPYING'
        if (-not [IO.File]::Exists($licenseDest)) { [IO.File]::Copy($licenseSource,$licenseDest,$false) }
        if (-not (Test-TreeComplete $dolphinDest @($assets.dolphin))) { throw 'Dolphin support files are incomplete.' }
    }
    # Place the small launcher next to AMretro so the existing bare
    # 'retroarch.exe' setting resolves without changing the user's settings.
    # Never replace an unrelated existing executable.
    # The launcher sits in AMretro's own install folder, so an existing valid
    # copy is an earlier AMretro launcher and is replaced with this version.
    $wrapperTemp = Join-Path $InstallDir ('.amretro-launcher-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::Copy($Launcher,$wrapperTemp,$false)
        if ([IO.File]::Exists($wrapper)) { [IO.File]::Replace($wrapperTemp,$wrapper,($wrapper + '.amretro-old-' + [Guid]::NewGuid().ToString('N'))) } else { [IO.File]::Move($wrapperTemp,$wrapper) }
        Get-ChildItem -LiteralPath $InstallDir -Filter 'retroarch.exe.amretro-old-*' -ErrorAction SilentlyContinue | Remove-Item -Force
    } finally { if ([IO.File]::Exists($wrapperTemp)) { [IO.File]::Delete($wrapperTemp) } }
    if ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $Launcher -Algorithm SHA256).Hash) { throw 'RetroArch launcher verification failed.' }
    # A user-supplied working RetroArch path stays untouched. Repair only a
    # non-default invalid path, after a full online backup and compare-and-swap.
    if (-not [string]::IsNullOrWhiteSpace($retroPath) -and $retroPath -ine 'retroarch.exe') {
        $good = Test-Path -LiteralPath $retroPath -PathType Leaf
        if (-not $good -and -not [IO.Path]::IsPathRooted($retroPath)) { $good = [bool](Get-Command $retroPath -ErrorAction SilentlyContinue) }
        if (-not $good) {
            $backup = $db + '.before-runtime-' + [Guid]::NewGuid().ToString('N') + '.backup'
            [AMretroSetupSqlite]::Repair($db,$retroPath,$wrapper,$backup)
            Write-Host 'Invalid old RetroArch path was repaired after a settings backup.'
        } else { Write-Host 'Existing configured RetroArch preserved.' }
    }
    Write-Host ('AMRETRO_DEPENDENCIES_READY cores=' + $names.Count)
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
