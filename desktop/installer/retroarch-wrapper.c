/* AMretro's per-installation RetroArch launcher.
 * RetroArch remains a separate, unmodified program in AMretroRuntime.
 * No shell is involved: the original argument tail is passed to CreateProcessW.
 */
#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>
#include <stdlib.h>

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR arguments, int show) {
    (void)instance; (void)previous; (void)show;
    wchar_t directory[32768];
    DWORD n = GetModuleFileNameW(NULL, directory, (DWORD)(sizeof(directory)/sizeof(directory[0])));
    if (!n || n >= sizeof(directory)/sizeof(directory[0])) goto failed;
    wchar_t *slash = wcsrchr(directory, L'\\');
    if (!slash) goto failed;
    *slash = L'\0';
    const wchar_t *suffix = L"\\AMretroRuntime";
    size_t base = wcslen(directory), extra = wcslen(suffix);
    if (base + extra + 1 >= sizeof(directory)/sizeof(directory[0])) goto failed;
    wcscat(directory, suffix);

    wchar_t target[32768];
    if (wcslen(directory) + 16 >= sizeof(target)/sizeof(target[0])) goto failed;
    wcscpy(target, directory);
    wcscat(target, L"\\retroarch.exe");
    if (GetFileAttributesW(target) == INVALID_FILE_ATTRIBUTES) goto failed;

    size_t tail = arguments ? wcslen(arguments) : 0;
    size_t command_len = wcslen(target) + tail + 5;
    if (command_len >= 32767) goto failed;
    wchar_t *command = (wchar_t *)calloc(command_len + 1, sizeof(wchar_t));
    if (!command) goto failed;
    wcscat(command, L"\""); wcscat(command, target); wcscat(command, L"\"");
    if (tail) { wcscat(command, L" "); wcscat(command, arguments); }
    STARTUPINFOW start; PROCESS_INFORMATION child;
    ZeroMemory(&start, sizeof(start)); ZeroMemory(&child, sizeof(child));
    start.cb = sizeof(start);
    BOOL ok = CreateProcessW(target, command, NULL, NULL, FALSE, 0, NULL, directory, &start, &child);
    free(command);
    if (!ok) goto failed;
    CloseHandle(child.hThread); CloseHandle(child.hProcess);
    return 0;
failed:
    MessageBoxW(NULL,
        L"موتور اجرای بازی AMretro پیدا نشد یا اجرا نشد. نصب‌کننده AMretro را دوباره اجرا کن.",
        L"AMretro", MB_OK | MB_ICONERROR);
    return 1;
}
