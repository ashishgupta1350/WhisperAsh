# End-to-end WhisperAsh test: focus a target app, press the dictation hotkey,
# play a clip through the speakers (the mic hears it), press the hotkey again,
# then read back the text that WhisperAsh pasted.
#
#   powershell -ExecutionPolicy Bypass -File e2e-dictate.ps1 -Wav en-fillers.wav -Target notepad
#   powershell -ExecutionPolicy Bypass -File e2e-dictate.ps1 -Wav en-short.wav -Target browser
#
# Needs: WhisperAsh running with hotkey Right Ctrl in Tap mode, speakers on, mic on.
param(
  [Parameter(Mandatory = $true)][string]$Wav,
  [ValidateSet('notepad', 'browser')][string]$Target = 'notepad',
  [int]$TimeoutSec = 20
)
$ErrorActionPreference = 'Stop'
if (-not [IO.Path]::IsPathRooted($Wav)) { $Wav = Join-Path $PSScriptRoot $Wav }

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WaKeys {
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  // Right Ctrl is an extended key (flag 1); key-up adds flag 2.
  public static void Down(byte vk) { keybd_event(vk, 0x1D, 1, UIntPtr.Zero); }
  public static void Up(byte vk) { keybd_event(vk, 0x1D, 3, UIntPtr.Zero); }
}
"@
$VK_RCONTROL = 0xA3

function Press-Hotkey {
  # WhisperAsh default: tap Right Ctrl to start, tap again to stop.
  [WaKeys]::Down($VK_RCONTROL); Start-Sleep -Milliseconds 60
  [WaKeys]::Up($VK_RCONTROL)
}

function Get-EditText($root) {
  $ctProp = [System.Windows.Automation.AutomationElement]::ControlTypeProperty
  $c1 = New-Object System.Windows.Automation.PropertyCondition -ArgumentList $ctProp, ([System.Windows.Automation.ControlType]::Document)
  $c2 = New-Object System.Windows.Automation.PropertyCondition -ArgumentList $ctProp, ([System.Windows.Automation.ControlType]::Edit)
  $cond = New-Object System.Windows.Automation.OrCondition -ArgumentList @([System.Windows.Automation.Condition[]]@($c1, $c2))
  $els = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
  $texts = @()
  foreach ($el in $els) {
    try {
      $tp = $el.GetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern)
      $texts += $tp.DocumentRange.GetText(-1)
      continue
    } catch {}
    try {
      $vp = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
      $texts += $vp.Current.Value
    } catch {}
  }
  return ($texts -join "`n")
}

# 1. Open the target
if ($Target -eq 'notepad') {
  $proc = Start-Process notepad.exe -PassThru
  Start-Sleep -Seconds 2
  $win = $null
  for ($i = 0; $i -lt 20 -and -not $win; $i++) {
    $p = Get-Process notepad -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Sort-Object StartTime -Descending | Select-Object -First 1
    if ($p) { $win = $p.MainWindowHandle } else { Start-Sleep -Milliseconds 250 }
  }
  [WaKeys]::SetForegroundWindow($win) | Out-Null
  Start-Sleep -Milliseconds 300
  [System.Windows.Forms.SendKeys]::SendWait('^n')   # fresh tab, so restored tabs don't interfere
  Start-Sleep -Milliseconds 600
} else {
  $page = Join-Path $PSScriptRoot 'textbox.html'
  $edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
  if (-not (Test-Path $edge)) { $edge = "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe" }
  Start-Process $edge -ArgumentList '--new-window', "file:///$($page -replace '\\','/')"
  Start-Sleep -Seconds 4
  $p = Get-Process msedge | Where-Object { $_.MainWindowTitle -like '*WhisperAsh test box*' } | Select-Object -First 1
  $win = $p.MainWindowHandle
  [WaKeys]::SetForegroundWindow($win) | Out-Null
  Start-Sleep -Milliseconds 500
}
$root = [System.Windows.Automation.AutomationElement]::FromHandle($win)
$before = Get-EditText $root

# 2. Dictate
$player = New-Object System.Media.SoundPlayer $Wav
$player.Load()
Press-Hotkey
Start-Sleep -Milliseconds 700           # let the mic open
$t0 = Get-Date
$player.PlaySync()
Start-Sleep -Milliseconds 300
Press-Hotkey
$released = Get-Date
$speechSec = [math]::Round(($released - $t0).TotalSeconds, 1)

# 3. Wait for the pasted text
$after = $before
while (((Get-Date) - $released).TotalSeconds -lt $TimeoutSec) {
  $after = Get-EditText $root
  if ($after -ne $before -and $after.Trim().Length -gt $before.Trim().Length) { break }
  Start-Sleep -Milliseconds 100
}
$appeared = Get-Date
$pasted = $after.Substring([math]::Min($before.Length, $after.Length)).Trim()
if (-not $pasted) { $pasted = $after.Trim() }

[pscustomobject]@{
  target          = $Target
  clip            = Split-Path $Wav -Leaf
  clipSeconds     = $speechSec
  msReleaseToText = if ($after -ne $before) { [int](($appeared - $released).TotalMilliseconds) } else { $null }
  text            = $pasted
} | Format-List
