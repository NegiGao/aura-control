-- Keep the desktop entry responsive while the bundled server is running.
property backendPID : ""
property backendPath : ""

on run
    my openControl()
end run

on reopen
    my openControl()
end reopen

on idle
    return 60
end idle

on openControl()
    set backendPath to (POSIX path of (path to me)) & "Contents/Resources/Backend.app/Contents/MacOS/"
    set backendName to do shell script "/usr/libexec/PlistBuddy -c 'Print CFBundleExecutable' " & quoted form of ((POSIX path of (path to me)) & "Contents/Resources/Backend.app/Contents/Info.plist")
    set backendPath to backendPath & backendName
    try
        set newPID to do shell script "/usr/bin/nohup " & quoted form of backendPath & " >/dev/null 2>&1 </dev/null & echo $!"
        if backendPID is "" then set backendPID to newPID
        try
            do shell script "/bin/kill -0 " & backendPID
        on error
            set backendPID to newPID
        end try
    on error errorText
        display alert "Aura Control" message errorText as critical
    end try
end openControl

on quit
    if backendPID is not "" and backendPath is not "" then
        try
            set commandText to do shell script "/bin/ps -p " & backendPID & " -o command="
            if commandText is backendPath then do shell script "/bin/kill -TERM " & backendPID
        end try
    end if
    continue quit
end quit
