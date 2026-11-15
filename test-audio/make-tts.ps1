# Makes English test clips with the built-in Windows voice (16 kHz mono WAV).
# Re-runnable; overwrites the same files.
Add-Type -AssemblyName System.Speech
$out = $PSScriptRoot
$clips = [ordered]@{
  'en-fillers'    = 'Um, so I wanted to, uh, tell you that the meeting is on Tuesday. No wait, I mean Wednesday at 3 PM. Please send the report before that.'
  'en-short'      = 'Thanks, see you tomorrow.'
  'en-list'       = 'Shopping list. First, milk. Second, eggs. Third, two kilos of rice. And, uh, some bread.'
  'hinglish-roman' = 'Haan bhai, kal ki meeting cancel ho gayi hai, so let us meet on Friday, theek hai?'
}
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.Rate = 0
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo 16000, ([System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen), ([System.Speech.AudioFormat.AudioChannel]::Mono)
foreach ($name in $clips.Keys) {
  $file = Join-Path $out "$name.wav"
  $synth.SetOutputToWaveFile($file, $fmt)
  $synth.Speak($clips[$name])
  $synth.SetOutputToNull()
  "{0}  {1:N0} KB" -f $file, ((Get-Item $file).Length / 1KB)
}
$synth.Dispose()
