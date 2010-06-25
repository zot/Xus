<html>
<body>
<?php 
$dir = "/usr/local/lib/php/extensions/no-debug-non-zts-20060613";
$dir = "/usr/local/lib/php/extensions";
$dir = "/dev/shm";

$d = opendir($dir);
while (false !== ($file = readdir($d))) {
	echo "$file<br>\n";
}
closedir($d);
?></body></html>