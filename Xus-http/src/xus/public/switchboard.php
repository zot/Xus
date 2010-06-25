#!/usr/bin/php5
<?php
	$commands = array(
		//"REQUESTKEY" => function () {}
	);
//	function h($str) {header($str);}
//	function endHeaders() {}
	function h($str) {echo("$str\n");}
	function endHeaders() {echo("---\n");}

	h("Content-Type: text/plain");
	endHeaders();
	echo("Host: {$_SERVER["HTTP_HOST"]}\n");
	echo("Duh: {$_SERVER["HTTP_DUH"]}\n");
?>
