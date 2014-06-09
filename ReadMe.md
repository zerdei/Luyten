Luyten
======
Decompiler Gui for Procyon<br><br>


### Debuginfo: Experimental branch
Purpose of this new feature is to create debug info tables from scratch (based on decompiled data), and merge these back into the original .class files.<br><br>

This will enable debugers to place breakpoints and show variables, while running the untouched original binary code.<br><br>

Usage: open a jar file like usual, then select File / Save All Debugable... menu.<br><br>

The attached example consists of two parts, first part helps setup your development environment and creates a normal Android hello world .apk, the secon part shows the placement of decompiled artifacts of this .apk in order to run it in debug mode. (You have to use Maven and Eclipse for both projects.)<br><br>

(Note: The debuginfo feature is far from ready, now it works, but can contain several unknown bugs and issues, and strongly tied to the current version of Procyon and Asm.)<br><br>


## Powered by 
*****
### Procyon
&copy; 2013 Mike Strobel<br>
[https://bitbucket.org/mstrobel/procyon/overview](https://bitbucket.org/mstrobel/procyon/overview)<br>
[Apache License](https://github.com/deathmarine/Luyten/blob/master/distfiles/Procyon.License.txt)<br>


### RSyntaxTextArea
&copy; 2012 Robert Futrell<br>
[http://fifesoft.com/rsyntaxtextarea/](http://fifesoft.com/rsyntaxtextarea/)<br>
[All Rights Reserved](https://github.com/deathmarine/Luyten/blob/master/distfiles/RSyntaxTextArea.License.txt)<br>