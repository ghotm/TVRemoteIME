// 特性检测不可靠（现代浏览器均含 ontouchend 接口），改为同时支持触摸与鼠标/遥控器（click）
var isSupportTouch = ("ontouchstart" in window) || (navigator.maxTouchPoints > 0);
// 鼠标/触摸按下后浏览器会补发 click，用此时间戳避免重复发送
var suppressClickTime = 0;
var processbar2=$("#processbar2");
var tabs = $('.tab-content');
var keyActionTimer = null;
var curKeyState = 0;
var curKeyCode = "";
var curPath = "";
var selectedPaths = [];
var selectedPathId = 0;
var fileOperItems = $('.file-oper-items');

function formatSize(size){
	if(size < 1024){
		return size + " B";
	}else if(size < (1024 * 1024)){
		return (size / 1024).toFixed(1) + " KB";
	}else if(size < (1024 * 1024 * 1024)){
		return (size / (1024 * 1024)).toFixed(1) + " MB";
	}else{
		return (size / (1024 * 1024 * 1024)).toFixed(1) + " GB";
	}
}
function parseTVData(text){
	var tv = [];
	var lines = text.split('\n');
	var offset = 0;
	while(offset < lines.length){
		var line = lines[offset].trim();
		if(line.length > 2 && line.substr(0, 1) == "[" && line.substr(line.length - 1, 1) == "]"){
			var name = line.substring(1, line.length - 1);
			var urls = [];
			offset ++;
			while(offset < lines.length){
				line = lines[offset].trim();
				if(line.length > 1){
					var c = line.substr(0, 1);
					if(c == '[') break;
					if(c != '#'){
						var p = line.indexOf('=');
						if(p > 0){
							var item = {
								"name":line.substring(0, p).trim(),
								"url": line.substring(p + 1).trim()
							};
							if(item.name.length && item.url.length) urls.push(item);
						}
					}
				}
				offset ++;
			}
			if(urls.length)tv.push({"name":name, "urls":urls});
		}else{
			offset ++;
		}
	}
	return tv;
}
function postKeyCode(keyCode){
	$.post("/key",{code:keyCode},function(data){
		console.log(data);
	});
}
// 长按按键起始时间，用于超时熔断（防止 touchend 丢失时无限重发 keydown）
var keyActionStartTime = 0;
function postKeyActionCode(keyCode, keyAction){
	curKeyCode = keyCode;
	curKeyState = keyAction;
	if(keyAction == 1){
		keyActionStartTime = +new Date();
	}
	var action = function(){
		var path = keyAction == 1 ? "/keydown" : "/keyup";
		$.post(path,{code:keyCode},function(data){
			console.log(data);
			// 熔断保护：超过 5 秒自动停止重发，避免 touchend 丢失导致持续发送按键
			if(curKeyState == 1 && curKeyCode == keyCode && (+new Date() - keyActionStartTime < 5000)){
				keyActionTimer = setTimeout(action, 100);
			}else{
				keyActionTimer = null;
			}
		});
	}
	if(keyAction == 2){
		if(keyActionTimer){
			clearTimeout(keyActionTimer);
			keyActionTimer = null;
		}
	}
	action();
}
// 全局兜底：手指滑出按键元素后在别处抬起时，元素级 touchend 不会触发，
// 这里在 document 级别监听并复位按键状态、补发一次 keyup，避免系统持续长按
$(document).on("touchend touchcancel mouseup mouseleave", function(){
	if(keyActionTimer){
		clearTimeout(keyActionTimer);
		keyActionTimer = null;
	}
	if(curKeyState == 1){
		curKeyState = 2;
		postKeyActionCode(curKeyCode, 2);
	}
	keyActionStartTime = 0;
});
function clickApp(id,type){
	var app=$("#app-"+id);
	if(2==type&&!confirm("是否确认要卸载应用["+app.text()+"]？"))return;
	if(2==type&&!confirm("再次确认：将卸载应用["+app.text()+"]，卸载后不可恢复！"))return;
	$.post(1==type?"/run":"/uninstall",{packageName:app.attr("data-packageName")},function(data){
		if(1==type)return;
		var code=(data&&data.code)?data.code:(("ok"==data)?"ok":"error");
		var msg=(data&&data.msg)?data.msg:"";
		if("ok"==code){
			alert("卸载成功！");
			setTimeout(reloadAppList,1e3);
		}else if("launch"==code){
			alert("已打开系统卸载界面，请在设备上确认卸载。\n若设备未显示卸载界面，可尝试在设备上开启网络调试(ADB)后重试。");
			setTimeout(reloadAppList,3e3);
		}else{
			alert("卸载失败："+(msg||"未知原因")+"\n可在设备上开启网络调试(ADB)后重试，或手动卸载。");
		}
	});
}
function postFileAction(action){
	if(selectedPaths.length == 0) return;

	var title = action == "copy" ? "是否确认要将所有选择的目录或者文件复制到当前目录下？" :
		        action == "cut" ? "是否确认要将所有选择的目录或者文件剪切到当前目录下？" :
		                          "是否确认要删除所有选择的目录或者文件？不可恢复！";
	if(confirm(title)){
		if(action == "delete" && !confirm("请再次确认是否要删除所有选择的目录或者文件？不可恢复！"))return;
		$.post("/file/" + action,{targetPath : curPath, paths:selectedPaths.join('|')},function(data){
			if("ok"==data){
				selectedPaths = [];
				fileOperItems.empty();
				$('.file-operations').addClass('hidden');
				selectedPathId = 0;
				setTimeout(function(){
					loadFileList(curPath);
				},1000);
			}
		});
	}
}
function postInstallApk(){
	if(selectedPaths.length == 0) return;

	var apkPaths = [];
	for(var i=0;i<selectedPaths.length;i++){
		var p = selectedPaths[i];
		if(p && /\.apk$/i.test(p)){
			apkPaths.push(p);
		}
	}
	if(apkPaths.length == 0){
		alert("选中的文件中没有 APK 文件！");
		return;
	}
	if(!confirm("是否确认安装选中的 " + apkPaths.length + " 个 APK 文件？"))return;
	$.post("/file/install",{paths:apkPaths.join('|')},function(data){
		if(data && data.success){
			alert("已发起安装，请留意电视屏幕上的安装确认。");
			selectedPaths = [];
			fileOperItems.empty();
			$('.file-operations').addClass('hidden');
			selectedPathId = 0;
			setTimeout(function(){
				loadFileList(curPath);
			},1000);
		}else{
			alert("安装失败！");
		}
	});
}
function getDiskSpace(){
	$.get("/sdcard_stat", null, function(data){
		$('#diskSpace').html('存储总容量：' + formatSize(data.totalBytes) + '，可用容量：' + formatSize(data.availableBytes));
	});
}
function reloadAppList(){
	$.post("/apps",{system:$("#cbListSystem")[0].checked},function(data){
		var appList=$(".app-list");
		appList.empty();
		var html=[];
		for(var i=0;i<data.length;i++){
			var app=data[i];
			html.push('<div class="app-item">');
			html.push('<img src="/icon/'+app.packageName+'" class="app-icon" />');
			html.push('<div class="app-name'+(app.isSysApp?" blue":"")+'" id="app-'+i+'" data-packageName="'+app.packageName+'">'+app.lable+"</div>");
			html.push('<div class="app-pkg">'+app.packageName+"</div>");
			html.push('<div class="app-btn">');
			if(app.isSysApp){
				html.push('   <input type="button" value="运行" class="btn" onclick="clickApp('+i+', 1);" />');
			}else{
				html.push('   <input type="button" value="运行" class="btn1 app-btn1" onclick="clickApp('+i+', 1);" />');
				html.push('\t  <input type="button" value="卸载" class="btn2 app-btn1" onclick="clickApp('+i+', 2);" />');
			}
			html.push("</div>");
			html.push("</div>");
		}
		appList.html(html.join("\r\n"));
	});
}
function loadFileList(path){
	curPath = path;
	$('#curPath').text(curPath == '' ? '/' : curPath);
	$.get("/file/dir/" + encodeURIComponent(path),null,function(data){
		var fileList=$(".file-list");
		fileList.empty();
		var html=[];
		if(data.parent != undefined){
			html.push('<div class="file-item"><div class="file-icon-panel">');
			html.push('<img src="/ic_dl_folder.png" class="file-icon" onclick="loadFileList(\''+data.parent+'\');" />');
			html.push('</div><div class="file-name">..</div>');
			html.push('</div>');
		}
		for(var i=0;i<data.dirs.length;i++){
			var file=data.dirs[i];
			html.push('<div class="file-item"><div class="file-icon-panel">');
			html.push('<img src="/ic_dl_folder.png" class="file-icon" onclick="loadFileList(\''+file.path+'\');" />');
			html.push('</div><div class="file-name">'+file.name+'</div>');
			html.push('<div class="app-btn">');
			html.push('<input type="button" value="选择" class="fbtn2 app-btn1" onclick="addFile(1,\''+file.name+'\',\''+file.path+'\');" />');
			html.push("</div>");
			html.push('</div>');
		}
		for(var i=0;i<data.files.length;i++){
			var file=data.files[i];
			html.push('<div class="file-item"><div class="file-icon-panel">');
			if(file.isMedia){
				html.push('<img src="ic_dl_video.png" class="file-icon" border="0" onclick="playMedia(this)" uri="' + file.fullPath + '" />');
			}else{
				html.push('<img src="ic_dl_other.png" class="file-icon" border="0" />');
			}
			html.push('<div class="' + (file.isMedia ? 'media-size' : 'file-size') + '">' + formatSize(file.size) + '</div>');
			html.push('</div><div class="file-name">'+file.name+'</div>');
			html.push('<div class="app-btn">');
			html.push('<a href="/file/download/' + file.path + '" target="_blank" class="">');
			html.push('<input type="button" value="下载" class="fbtn1 app-btn1" />');	
			html.push("</a>");
			html.push('\t  <input type="button" value="选择" class="fbtn2 app-btn1" onclick="addFile(2,\''+file.name+'\',\''+file.path+'\');" />');
			html.push("</div>");
			html.push('</div>');
		}
		fileList.html(html.join("\r\n"));
	});
}
function addFile(type, name, path){
	for(var i=0; i<selectedPaths.length; i++){
		if(selectedPaths[i] == path) return;
	}
	selectedPaths.push(path);
	selectedPathId ++;
	var html = [];
	html.push('<div class="file-oper-item" onclick="removeFile(' + selectedPathId + ',\'' + path + '\');" id="fileOperItem' + selectedPathId + '">');
	html.push('<div class="file-oper-name">');
	html.push(type == 1 ? "目录" : "文件");
	html.push("：");
	html.push(name);
	html.push('</div><div class="file-oper-del">X</div></div>');
	fileOperItems.append(html.join(''));
	$('.file-operations').removeClass('hidden');
}
function removeFile(id, path){
	var rid = -1;
	for(var i=0; i<selectedPaths.length; i++){
		if(selectedPaths[i] == path){
			rid = i;
			break;
		}
	}
	if(rid != -1){
		selectedPaths.splice(rid, 1);
	}
	$('#fileOperItem' + id).remove();
	if(selectedPaths.length == 0){
		selectedPathId = 0;
		$('.file-operations').addClass('hidden');
	}
}
function loadTVList(){
	$.get("/tv.txt",null,function(text){
		var tvItems=$(".tv-items");
		tvItems.empty();
		$('#tvData').val(text);
		var data = parseTVData(text);
		var html=[];
		for(var i=0;i<data.length;i++){
			var tv=data[i];
			html.push('<div class="tv-item">');
			html.push(tv.name);
			html.push('<br />');
			for(var j=0; j<tv.urls.length; j++){
				html.push('<a class="tv-source" data-video="' + tv.urls[j].url + '" onclick="playTV(this)">' + tv.urls[j].name + '</a>');
			}
			html.push('</div>');
		}
		tvItems.html(html.join("\r\n"));
	}, "text");
}
$("#confirm").on("click",function(){
	var $input=$("#inputarea");
	var text=$input.val();
	if(""!=text){
		$input.val("");
		$.post("/text",{text:text},function(data){
			console.log(data);
		});
	}
})
$("#cbUninstall").on("click",function(){
	if(this.checked){
		$(".btn1").addClass("hide");
		$(".btn2").removeClass("hide");
	}
	else{
		$(".btn1").removeClass("hide");
		$(".btn2").addClass("hide");
	}
})
$('#btnShowTVEdit,#btnTVEdit,#btnTVCancel').on('click',function(){
	switch(this.id){
		case 'btnShowTVEdit':
			$(".tv-items").addClass("hidden");
			$(".tv-editor").removeClass("hidden");
			break;
		case 'btnTVCancel':
			$(".tv-editor").addClass("hidden");
			$(".tv-items").removeClass("hidden");
			break;
		case 'btnTVEdit':
			$.post("/tv.txt",{text:$('#tvData').val()},function(data){
				console.log(data);
				if(data == "ok"){
					alert('电视直播源已修改成功！');
					loadTVList();
				}else{
					alert('电视直播源修改失败！');
				}
				$(".tv-editor").addClass("hidden");
				$(".tv-items").removeClass("hidden");
			});
			break;
	}
});
$("#cbFileSelect").on("click",function(){
	if(this.checked){
		$(".fbtn1").addClass("hide");
		$(".fbtn2").removeClass("hide");
	}
	else{
		$(".fbtn1").removeClass("hide");
		$(".fbtn2").addClass("hide");
	}
})
// Tab切换 - 支持新的button.tab结构
$("button.tab, div.tab").on("click", function(){
	var o = $(this);
	$(".tab.active, .cur").removeClass("active cur");
	tabs.addClass("hidden hide");
	var tabName = o.attr('data-tab') || o.attr('data-rel');
	tabs.filter('[data-tab="' + tabName + '"]').removeClass("hidden hide");
	o.addClass('active cur');
})
$("#btnCls").on("click",function(){
	postKeyCode($(this).attr("data-key"))
})
// 方向键 - 支持长按重复发送，同时兼容触摸与鼠标/遥控器（click）
$(".direction-btn, .direction, #btnDel").on("mousedown touchstart",function(e){
		if(e.type === 'touchstart') e.preventDefault(); // 阻止合成 mouse 事件导致按键双发
		var o=$(this);
		suppressClickTime = +new Date() + 600;
		$("#direction-btns").css({"background-position":o.attr("data-bp")});
		postKeyActionCode(o.attr("data-key"), 1);
		console.log("onkeydown:" + o.attr("data-key"));
})
$(".direction-btn, .direction, #btnDel").on("mouseup touchend touchcancel",function(e){
		if(e.type === 'touchend') e.preventDefault(); // 阻止合成 mouse 事件导致按键双发
		var o=$(this);
		postKeyActionCode(o.attr("data-key"), 2);
		console.log("onkeyup:" + o.attr("data-key"));
})
// 方向键点击兜底：遥控器/键盘 Enter 直接触发 click（无 mousedown/touchstart）
$(".direction-btn, .direction, #btnDel").on("click",function(){
		if(+new Date() < suppressClickTime) return;
		postKeyCode($(this).attr("data-key"));
})
// 控制按钮（返回、菜单、主页、音量等）- 使用click事件发送单次按键
$(".control-btn").on("click", function(){
	var o=$(this);
	var keyCode = o.attr("data-key");
	console.log("control-btn click:" + keyCode);
	postKeyCode(keyCode);
})
$(".otherbtn").on("mousedown touchstart", function(e) {
	if(e.type === 'touchstart') e.preventDefault(); // 阻止合成 mouse 事件导致按键双发
	var o = $(this);
	suppressClickTime = +new Date() + 600;
	o.css({
		"background-position": o.attr("data-bp")
	});
	postKeyCode(o.attr("data-key"));
})
// 其他按钮点击兜底：遥控器/键盘 Enter 直接触发 click
$(".otherbtn").on("click", function() {
	if(+new Date() < suppressClickTime) return;
	postKeyCode($(this).attr("data-key"));
})
$(".direction-btn,.direction,.otherbtn").on("mouseup touchend touchcancel touchmove mouseleave", function() {
	$("#direction-btns,.direction-btn,.direction,.otherbtn").css({
		"background-position": ""
	});
})
$("#cbListSystem").on("click", reloadAppList);
$("#showSettings").on("click", function() {
	$.post("/runSystem", {
		packageName: 'android.settings.SETTINGS'
	}, function(data) {
		console.log(data)
	})
})
$("#btnPlay").on("click", function() {
	var url = $('#playUrl').val();
	if (url.length > 0 && (url.indexOf('http://') == 0 
		|| url.indexOf('https://') == 0 
		|| url.indexOf('thunder://') == 0 
		|| url.indexOf('ed2k://') == 0 
		|| url.indexOf('ftp://') == 0 
		|| url.indexOf('rtmp://') == 0 
		|| url.indexOf('rtmps://') == 0
		|| url.indexOf('mms://') == 0)){
			$.post("/play", {playUrl: url, "useSystem":$('#playUseSystem')[0].checked}, function(data) {
				console.log(data)
			})
	}else{
		alert('请输入正确的网络视频地址，只支持http/ftp/thunder/ed2k/rtmp/mms。');
	}
})
function playMedia(obj){
	$.post("/play", {playUrl: $(obj).attr('uri'), "useSystem":$('#playUseSystem')[0].checked}, function(data) {
		console.log(data)
	})
}
$('#stopPlay').on("click", function(){
	$.post("/playStop",null, function(data) {
		console.log(data)
		alert('已发送停止播放指令。若画面未退出，请稍候或手动按遥控器返回键。')
	})
});
$('#playUseSystem').on("click", function(){
	if(this.checked){
		alert('调用外部播放器不会自动结束边下边播任务，结束播放后请手动点击停止！');
	}
	var time = new Date(9998, 1,1);
	if(!this.checked)time = new Date(1900, 1, 1);
	document.cookie = "playUseSystem=" + (this.checked ? "1" : "0") + "; expires=" + time.toGMTString();
});
$(function(){
	$('#playUseSystem')[0].checked = document.cookie.indexOf('playUseSystem=1') != -1;
});

$('#speedInterval').on("change", function(){
	$.post("/changePlayFFI", {speedInterval: this.value}, function(data) {
		console.log(data)
	})
});
function playTV(o){
	$.post("/play", {playUrl: $(o).attr('data-video'), "useSystem":$('#playUseSystem')[0].checked}, function(data) {
		console.log(data)
	})
}
$("#btnClear").on("click", function() {
	if(confirm("是否要删除所有传送的文件？")){
		$.post("/clearCache", null, function(data) {
			console.log(data);
			alert("传送的文件都已清除完毕！")
		})
	}
})
// 上传按钮点击 → 触发对应的文件选择 input（input 通过样式隐藏，需按钮触发）
/* ========== 安装 APK（重新实现） ========== */
var apkInstallFile = null;
$("#btnPickApk").on("click", function(){ $("#apkFileInput")[0].click(); });
$("#apkFileInput").on("change", function(){
	apkInstallFile = this.files && this.files[0];
	if(apkInstallFile){
		var isApk = apkInstallFile.name.toLowerCase().indexOf(".apk") != -1;
		$("#apkFileName").text(apkInstallFile.name + "（" + formatSize(apkInstallFile.size) + "）" + (isApk ? "" : "（非APK文件，不会自动安装）"));
	}else{
		$("#apkFileName").text("未选择文件");
	}
});
$("#btnInstallApk").on("click", function(){
	if(!apkInstallFile){ alert("请先选择要上传的 APK 文件"); return; }
	var autoInstall = $("#cbAutoInstallApk").is(":checked");
	$("#apkProgressWrap").removeClass("hidden");
	$("#processbarApk").css("width", "1%");
	$("#apkProgressWrap .progress-text").text("0%");
	$("#uploadStatus").text("正在上传…");
	var formData = new FormData();
	formData.append("file", apkInstallFile, encodeURI(apkInstallFile.name));
	formData.append("autoInstall", autoInstall);
	$.ajax({
		type: "POST",
		url: "/upload",
		data: formData,
		processData: false,
		contentType: false,
		xhr: function(){
			var xhr = new XMLHttpRequest();
			if(xhr.upload){
				xhr.upload.onprogress = function(e){
					if(e.lengthComputable){
						var pct = Math.floor(100 * e.loaded / e.total);
						$("#processbarApk").css("width", pct + "%");
						$("#apkProgressWrap .progress-text").text(pct + "%");
						$("#uploadStatus").text("正在上传… " + pct + "%");
					}
				};
			}
			return xhr;
		},
		success: function(data){
			if(data && data.success){
				$("#processbarApk").css("width", "100%");
				$("#apkProgressWrap .progress-text").text("100%");
				if(data.filePath && data.filePath.toLowerCase().indexOf(".apk") != -1 && autoInstall){
					$("#uploadStatus").text("上传成功，已发起安装！请留意电视屏幕上的安装确认。");
				}else{
					$("#uploadStatus").text("上传成功！文件保存在：" + (data.filePath || "电视存储"));
				}
				apkInstallFile = null;
				$("#apkFileInput").val("");
				$("#apkFileName").text("未选择文件");
			}else{
				$("#uploadStatus").text("上传失败：" + ((data && data.message) || "未知错误"));
			}
		},
		error: function(){
			$("#uploadStatus").text("上传失败：无法连接电视服务，请确认输入法服务已启用。");
		}
	});
});
/* ========== == ========== */

$("#btnUpload2").on("click", function(){ $("#upfile2")[0].click(); });
$("#btnUploadTorrent").on("click", function(){ $("#upfile3")[0].click(); });

$("#upfile2,#upfile3").change(function() {
	var id = this.id;
	var formData = new FormData;
	var file = this.files[0];
	var processbar = null;
	if(id == "upfile2"){
		formData.append("path", curPath);
		processbar = processbar2;
	}
	formData.append("file", file, encodeURI(file.uploadName || file.name));
	$.ajax({
		type: "POST",
		url: id == "upfile2" ? "/file/upload" : "/torrent/upload",
		dataType: "json",
		data: formData,
		processData: false,
		contentType: false,
		xhr: function() {
			var xhr = $.ajaxSettings.xhr();
			if(xhr.upload){
				xhr.upload.addEventListener("progress", function(e) {
					if(processbar){
						var p = Math.floor(100 * e.loaded / e.total) + "%";
						processbar.css({
							width: p
						}).text(p);
					}
					if(e.loaded == e.total) $(id).val("");
				}, false);
			}
			return xhr;
		},
		beforeSend: function() {
			if(processbar){
				processbar.css({
					width: "1%"
				}).text("")
			}
		},
		success: function(data) {
			if(data.success){
				if(id == "upfile2"){
					loadFileList(curPath);
					alert("文件已成功上传到当前目录。");
				}else if(id == "upfile3"){
					alert("种子文件已上传并解析，请选择要播放的视频文件。");
					addTorrentItems(data);
				}
			}else{
				alert("抱歉，文件上传失败！");
			}
		}
	});
});
$('#btnPlayTorrent').on('click', function(){
	var torrentItems = $("#torrentItems");
	var videoIndex = torrentItems.val();
	if(videoIndex != ''){
		$.post('/torrent/play', {"videoIndex":videoIndex, "useSystem":$('#playUseSystem')[0].checked}, function(data){
			console.log(data);
		});
	}
});
function addTorrentItems(data){
	var torrentItems = $("#torrentItems");
	torrentItems.empty();
	if(data.files && data.files.length){
		for(var i=0; i<data.files.length; i++){
			var f = data.files[i];
			torrentItems.append("<option value='" + f.index + "'>" + f.name + "(" + formatSize(f.size) + ")</option>");
		}
		torrentItems.val(data.files[0].index);
	}
}
function loadTorrentItems(){
	$.post('/torrent/data', null, function(data){
		if(data.success)addTorrentItems(data);
	});
}

var upgradeScript = null;

function upgrade() {
	$.get('/version', function(version){
		$('#curVer').html(version);
	});
	if(null != upgradeScript){
		document.body.removeChild(upgradeScript);
	}
	var upgradeScript = document.createElement("script");
	upgradeScript.type = "text/javascript";
	upgradeScript.src = "http://tvremoteime-1255402058.cos.ap-guangzhou.myqcloud.com/upgrade.js";
	document.body.appendChild(upgradeScript);
}
reloadAppList();
loadFileList("");
getDiskSpace();
loadTVList();
loadTorrentItems();
upgrade();
setInterval(function() {
	upgrade()
}, 18e5);

// ==================== 触摸板功能 ====================

var touchpad = {
	element: null,
	tracking: false,
	lastX: 0,
	lastY: 0,
	sensitivity: 1.5,
	swipeDistance: 300,
	longPressDuration: 600,
	fingers: 0,
	tapStartTime: 0,
	tapStartX: 0,
	tapStartY: 0,
	moveThreshold: 10,  // 移动阈值，超过此值不算点击
	hasMoved: false,
	lastMoveTime: 0,
	throttleInterval: 16,  // 约60fps的节流
	maxFingers: 0  // 本次触摸过程中出现过的最大手指数（用于判定双指右键）
};

// 初始化触摸板
function initTouchpad() {
	touchpad.element = document.getElementById('touchpad');
	if (!touchpad.element) return;

	// 触摸事件
	touchpad.element.addEventListener('touchstart', onTouchpadStart, { passive: false });
	touchpad.element.addEventListener('touchmove', onTouchpadMove, { passive: false });
	touchpad.element.addEventListener('touchend', onTouchpadEnd, { passive: false });
	touchpad.element.addEventListener('touchcancel', onTouchpadEnd, { passive: false });

	// 鼠标事件 (用于PC端测试)
	touchpad.element.addEventListener('mousedown', onTouchpadStart);
	touchpad.element.addEventListener('mousemove', onTouchpadMove);
	touchpad.element.addEventListener('mouseup', onTouchpadEnd);
	touchpad.element.addEventListener('mouseleave', onTouchpadEnd);

	// 阻止右键菜单
	touchpad.element.addEventListener('contextmenu', function(e) {
		e.preventDefault();
	});

	// 鼠标按钮
	$('#mouse-left').on('click touchend', function(e) {
		e.preventDefault();
		mouseClick(0);
	});
	$('#mouse-longclick').on('click touchend', function(e) {
		e.preventDefault();
		mouseLongClick();
	});
	$('#mouse-right').on('click touchend', function(e) {
		e.preventDefault();
		mouseClick(1);
	});

	// 上划下划按钮
	$('#swipe-up').on('click touchend', function(e) {
		e.preventDefault();
		mouseSwipeUp();
	});
	$('#swipe-down').on('click touchend', function(e) {
		e.preventDefault();
		mouseSwipeDown();
	});

	// 灵敏度调节
	$('#sensitivity').on('input', function() {
		touchpad.sensitivity = parseFloat(this.value);
		$('#sensitivity-value').text(touchpad.sensitivity.toFixed(1));
	});

	// 滑动距离调节
	$('#swipe-distance').on('input', function() {
		touchpad.swipeDistance = parseInt(this.value);
		$('#swipe-distance-value').text(touchpad.swipeDistance);
	});

	// 长按时间调节
	$('#longpress-duration').on('input', function() {
		touchpad.longPressDuration = parseInt(this.value);
		$('#longpress-duration-value').text(touchpad.longPressDuration);
	});

	console.log('Touchpad initialized');
}

// 获取事件坐标
function getEventPos(e) {
	if (e.touches && e.touches.length > 0) {
		return { x: e.touches[0].clientX, y: e.touches[0].clientY };
	} else if (e.changedTouches && e.changedTouches.length > 0) {
		return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
	} else {
		return { x: e.clientX, y: e.clientY };
	}
}

// 触摸开始
function onTouchpadStart(e) {
	e.preventDefault();
	var pos = getEventPos(e);
	touchpad.tracking = true;
	touchpad.lastX = pos.x;
	touchpad.lastY = pos.y;
	touchpad.tapStartX = pos.x;
	touchpad.tapStartY = pos.y;
	touchpad.tapStartTime = Date.now();
	touchpad.hasMoved = false;
	touchpad.fingers = e.touches ? e.touches.length : 1;
	touchpad.maxFingers = touchpad.fingers;
}

// 触摸移动
function onTouchpadMove(e) {
	if (!touchpad.tracking) return;
	e.preventDefault();

	// 节流
	var now = Date.now();
	if (now - touchpad.lastMoveTime < touchpad.throttleInterval) return;
	touchpad.lastMoveTime = now;

	var pos = getEventPos(e);
	var dx = (pos.x - touchpad.lastX) * touchpad.sensitivity;
	var dy = (pos.y - touchpad.lastY) * touchpad.sensitivity;

	// 检查是否超过移动阈值
	var totalMove = Math.abs(pos.x - touchpad.tapStartX) + Math.abs(pos.y - touchpad.tapStartY);
	if (totalMove > touchpad.moveThreshold) {
		touchpad.hasMoved = true;
	}

	// 更新手指数量（记录出现过的最大手指数，供双指右键判定）
	if (e.touches) {
		touchpad.fingers = e.touches.length;
		if (touchpad.fingers > touchpad.maxFingers) {
			touchpad.maxFingers = touchpad.fingers;
		}
	}

	if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
		if (touchpad.fingers >= 2) {
			// 双指滚动
			mouseScroll(Math.round(dy));
		} else {
			// 单指移动
			mouseMove(Math.round(dx), Math.round(dy));
		}
	}

	touchpad.lastX = pos.x;
	touchpad.lastY = pos.y;
}

// 触摸结束
function onTouchpadEnd(e) {
	if (!touchpad.tracking) return;
	e.preventDefault();

	var tapDuration = Date.now() - touchpad.tapStartTime;

	// 综合本次触摸过程中出现过的最大手指数，避免双指轻点被判为单指左键
	var fingers = Math.max(touchpad.fingers || 0, touchpad.maxFingers || 0);
	if (e.touches && e.touches.length > fingers) fingers = e.touches.length;
	if (e.changedTouches && e.changedTouches.length > fingers) fingers = e.changedTouches.length;

	// 快速点击且没有明显移动 = 点击
	if (tapDuration < 300 && !touchpad.hasMoved) {
		if (fingers >= 2) {
			mouseClick(1);  // 双指 = 右键
		} else {
			mouseClick(0);  // 单指 = 左键
		}
	}

	touchpad.tracking = false;
	touchpad.fingers = 0;
	touchpad.maxFingers = 0;
}

// 发送鼠标移动
function mouseMove(dx, dy) {
	if (dx === 0 && dy === 0) return;
	$.post("/mouse/move", { dx: dx, dy: dy }, function(data) {
		if (data && data.status === 'ok') {
			$('#mouse-pos').text('位置: ' + data.x + ', ' + data.y);
		}
	});
}

// 发送鼠标点击
function mouseClick(button) {
	$.post("/mouse/click", { button: button }, function(data) {
		console.log('click:', data);
		if (data && data.status === 'ok') {
			// 视觉反馈
			var btnId = button === 0 ? '#mouse-left' : (button === 1 ? '#mouse-right' : '#mouse-middle');
			$(btnId).css('transform', 'scale(0.95)');
			setTimeout(function() {
				$(btnId).css('transform', '');
			}, 100);
		} else if (data && data.status === 'error') {
			$('#adb-status').text('触控服务: 异常').removeClass('connected').addClass('disconnected');
		}
	});
}

// 发送鼠标滚动
function mouseScroll(dy) {
	if (dy === 0) return;
	$.post("/mouse/scroll", { dy: dy }, function(data) {
		console.log('scroll:', data);
	});
}

// 发送上划手势
function mouseSwipeUp() {
	$.post("/mouse/swipeup", { distance: touchpad.swipeDistance }, function(data) {
		console.log('swipe up:', data);
		if (data && data.status === 'ok') {
			// 视觉反馈
			$('#swipe-up').css('transform', 'scale(0.95)');
			setTimeout(function() {
				$('#swipe-up').css('transform', '');
			}, 100);
		}
	});
}

// 发送下划手势
function mouseSwipeDown() {
	$.post("/mouse/swipedown", { distance: touchpad.swipeDistance }, function(data) {
		console.log('swipe down:', data);
		if (data && data.status === 'ok') {
			// 视觉反馈
			$('#swipe-down').css('transform', 'scale(0.95)');
			setTimeout(function() {
				$('#swipe-down').css('transform', '');
			}, 100);
		}
	});
}

// 发送长按
function mouseLongClick() {
	$.post("/mouse/longclick", { duration: touchpad.longPressDuration }, function(data) {
		console.log('long click:', data);
		if (data && data.status === 'ok') {
			// 视觉反馈
			$('#mouse-longclick').css('transform', 'scale(0.95)');
			setTimeout(function() {
				$('#mouse-longclick').css('transform', '');
			}, 100);
		}
	});
}

// 检查触控服务状态
// 注意：必须使用只读的 /mouse/status，不能用 /mouse/move——后者会重置光标自动隐藏计时器，
// 导致已隐藏的光标被周期性唤醒而「长时间显示」
function checkAdbStatus() {
	$.get("/mouse/status", function(data) {
		if (data && data.serviceEnabled) {
			$('#adb-status').text('触控服务: 已启用').removeClass('disconnected').addClass('connected');
			$('#adb-enable').addClass('hidden');
		} else {
			$('#adb-status').text('触控服务: 未启用').removeClass('connected').addClass('disconnected');
			$('#adb-enable').removeClass('hidden');
		}
	}).fail(function() {
		$('#adb-status').text('触控服务: 未启用').removeClass('connected').addClass('disconnected');
		$('#adb-enable').removeClass('hidden');
	});
}

// 页面加载完成后初始化触摸板
$(document).ready(function() {
	initTouchpad();
	// 未启用触控服务时，引导用户去系统设置开启
	$('#adb-enable').on('click', function() {
		$.post("/mouse/openAccessibility", {}, function() {
			alert('已尝试打开设备的「辅助功能 / 无障碍」设置，\n请在其中找到「小盒精灵」并开启其触控服务。');
		}).fail(function() {
			alert('无法自动打开设置，请在设备上进入「设置 → 辅助功能 / 无障碍」中，\n手动开启「小盒精灵」的触控服务。');
		});
	});
	// 定期检查 ADB 状态
	checkAdbStatus();
	setInterval(checkAdbStatus, 10000);
});

// ===================== 影视仓 =====================
var movieCurrentDetail = null;

function movieLoadSources() {
	$.get("/movie/sources", function(data) {
		if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { return; } }
		if (!data || data.list === undefined) { return; }
		var lastUpdated = data.lastUpdated || 0;
		$('#movieSourcesStatus').text(lastUpdated ? '上次更新：' + new Date(lastUpdated).toLocaleString() : '尚未更新');
		var html = '';
		$(data.list).each(function() {
			var s = this;
			var cls = s.enable ? '' : ' disabled';
			var statusCls = 'good';
			if (s.status === 'caution') { statusCls = 'warn'; }
			else if (s.status === 'removed' || s.status === 'temporarily_unavailable') { statusCls = 'bad'; }
			html += '<div class="movie-source-item' + cls + '">'
				+ '<label class="checkbox-label movie-source-toggle-wrap"><input type="checkbox" class="movie-source-toggle" data-key="' + s.key + '"' + (s.enable ? ' checked' : '') + '><span>启用</span></label>'
				+ '<span class="movie-source-name">' + s.name + '</span>'
				+ '<span class="movie-source-status ' + statusCls + '" title="' + (s.lastProbeError || '') + '">' + (s.status || '') + '</span>'
				+ '<span class="movie-source-api" title="' + s.api + '">' + s.api + '</span>'
				+ '<button type="button" class="movie-source-del" data-key="' + s.key + '">删除</button>'
				+ '</div>';
		});
		$('#movieSourceList').html(html || '<div class="movie-empty">暂无源，请点击「检查更新源」</div>');
		$('.movie-source-toggle').off('click').on('click', function() {
			var key = $(this).attr('data-key');
			$.post('/movie/toggleSource', { key: key, enable: this.checked }, function() {
				movieLoadSources();
			});
		});
		$('.movie-source-del').off('click').on('click', function() {
			var key = $(this).attr('data-key');
			if (!confirm('确定删除该源？删除后不会被自动更新恢复（可重新添加或点「恢复内置源」）。')) { return; }
			$.post('/movie/removeSource', { key: key }, function(data) {
				if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { data = null; } }
				if (data && data.code === 'ok') {
					movieLoadSources();
				} else {
					alert(data && data.msg ? data.msg : '删除失败');
				}
			});
		});
	}).fail(function() {
		$('#movieSourcesStatus').text('源列表加载失败');
	});
}

function movieSearch() {
	var wd = ($('#movieSearchInput').val() || '').trim();
	if (!wd) { return; }
	$('#movieSearchStatus').text('搜索中...');
	$('#movieDetail').html('<div class="movie-empty">选择左侧影片查看详情</div>');
	movieCurrentDetail = null;
	$('#movieResultList').html('<div class="movie-empty">搜索中...</div>');
	$.get('/movie/search', { wd: wd }, function(data) {
		if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { data = null; } }
		if (!data || data.code !== 0) { $('#movieSearchStatus').text('搜索失败'); return; }
		$('#movieSearchStatus').text('共 ' + data.total + ' 个结果');
		if (!data.list || !data.list.length) {
			$('#movieResultList').html('<div class="movie-empty">未找到相关影视</div>');
			return;
		}
		var html = '';
		$(data.list).each(function() {
			var v = this;
			var pic = v.pic || '';
			html += '<div class="movie-card" data-source="' + v.source + '" data-id="' + v.id + '">'
				+ (pic ? '<div class="movie-card-pic"><img loading="lazy" src="' + pic + '" alt="海报" onerror="this.style.display=\'none\'"></div>' : '')
				+ '<div class="movie-card-body">'
				+ '<div class="movie-card-title">' + (v.name || '') + (v.year ? ' <span class="movie-card-year">' + v.year + '</span>' : '') + '</div>'
				+ (v.sub ? '<div class="movie-card-sub">' + v.sub + '</div>' : '')
				+ '<div class="movie-card-meta">' + (v.sourceName || '') + (v.actor ? ' · 主演：' + v.actor : '') + '</div>'
				+ (v.blurb ? '<div class="movie-card-blurb">' + v.blurb + '</div>' : '')
				+ '</div></div>';
		});
		$('#movieResultList').html(html);
		$('.movie-card').on('click', function() {
			movieDetail($(this).attr('data-source'), $(this).attr('data-id'));
		});
	}).fail(function() {
		$('#movieSearchStatus').text('搜索请求失败');
	});
}

function movieDetail(source, id) {
	$('#movieSearchStatus').text('加载详情...');
	$('#movieDetail').html('<div class="movie-empty">加载中...</div>');
	$.get('/movie/detail', { source: source, id: id }, function(data) {
		if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { data = null; } }
		if (!data || data.code !== 0 || !data.detail) {
			$('#movieDetail').html('<div class="movie-empty">详情加载失败</div>');
			$('#movieSearchStatus').text('');
			return;
		}
		$('#movieSearchStatus').text('');
		movieCurrentDetail = data.detail;
		var d = data.detail;
		var html = '<div class="movie-detail-head">'
			+ (d.pic ? '<div class="movie-detail-pic"><img src="' + d.pic + '" alt="海报"></div>' : '')
			+ '<div class="movie-detail-meta">'
			+ '<div class="movie-detail-title">' + (d.name || '') + '</div>'
			+ '<div class="movie-detail-line">' + (d.year ? d.year + ' · ' : '') + (d.sourceName || '') + '</div>'
			+ (d.director ? '<div class="movie-detail-line">导演：' + d.director + '</div>' : '')
			+ (d.actor ? '<div class="movie-detail-line">主演：' + d.actor + '</div>' : '')
			+ (d.blurb ? '<div class="movie-detail-blurb">' + d.blurb + '</div>' : '')
			+ '</div></div>';
		var lines = d.lines || [];
		$(lines).each(function() {
			var line = this;
			html += '<div class="movie-line"><div class="movie-line-flag">' + (line.flag || '') + '</div><div class="movie-line-eps">';
			$(line.eps || []).each(function() {
				var ep = this;
				html += '<button type="button" class="movie-ep" data-url="' + ep.url + '" data-name="' + (d.name || '') + ' ' + (ep.name || '') + '">' + (ep.name || '') + '</button>';
			});
			html += '</div></div>';
		});
		html += '<div class="movie-detail-actions"><button type="button" class="btn-primary" id="moviePlaySystemBtn">用系统播放器播放</button></div>';
		$('#movieDetail').html(html);
		$('.movie-ep').on('click', function() {
			var url = $(this).attr('data-url');
			var title = $(this).attr('data-name');
			if (!url) { return; }
			$.post('/play', { playUrl: url, forceVod: true, title: title }, function() {});
		});
		$('#moviePlaySystemBtn').on('click', function() {
			if (!movieCurrentDetail) { return; }
			var lines0 = movieCurrentDetail.lines || [];
			var first = null;
			if (lines0.length && (lines0[0].eps || []).length) { first = lines0[0].eps[0]; }
			if (!first) { alert('无播放地址'); return; }
			$.post('/play', { playUrl: first.url, forceVod: true, useSystem: true, title: (movieCurrentDetail.name || '') + ' ' + (first.name || '') }, function() {});
		});
		// 窄屏堆叠模式：滚动到详情
		var el = document.getElementById('movieDetail');
		if (el && el.scrollIntoView) { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
	}).fail(function() {
		$('#movieDetail').html('<div class="movie-empty">详情请求失败</div>');
	});
}

function movieRefreshPlayState() {
	$.get('/movie/playState', function(data) {
		if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { return; } }
		if (!data) { return; }
		if (data.isPlaying) {
			$('#moviePlayState').text('播放中：' + (data.lastPlayUrl || ''));
		} else {
			$('#moviePlayState').text('播放：未播放');
		}
		// 播放错误透出到 Web 端（便于诊断，如 ijkplayer err=-10000）
		if (data.lastPlayError) {
			var t = data.lastPlayErrorTime ? new Date(data.lastPlayErrorTime).toLocaleTimeString() : '';
			$('#playErrorStatus').removeClass('hidden')
				.text('播放错误（' + t + '）：' + data.lastPlayError + '　地址：' + (data.lastPlayUrl || ''));
		} else {
			$('#playErrorStatus').addClass('hidden').text('');
		}
	}).fail(function() {});
}

$('#movieSearchBtn').on('click', movieSearch);
$('#movieSearchInput').on('keydown', function(e) {
	if (e.keyCode === 13) { movieSearch(); }
});
$('#movieUpdateBtn').on('click', function() {
	$('#movieSourcesStatus').text('正在检查更新...');
	$.post('/movie/updateSources', null, function() {
		setTimeout(movieLoadSources, 3000);
		setTimeout(function() { $('#movieSourcesStatus').text('更新完成'); }, 6000);
	}).fail(function() {
		$('#movieSourcesStatus').text('更新请求失败');
	});
});
// 子导航：搜索 / 源管理（不带 data-tab，独立处理，避免误触发顶部 tab 委托）
$('.movie-subnav-btn').on('click', function() {
	var target = $(this).attr('data-subnav');
	$('.movie-subnav-btn').removeClass('active');
	$(this).addClass('active');
	if (target === 'source') {
		$('#movieSearchView').addClass('hidden');
		$('#movieSourceView').removeClass('hidden');
		movieLoadSources();
	} else {
		$('#movieSearchView').removeClass('hidden');
		$('#movieSourceView').addClass('hidden');
	}
});
// 恢复内置源
$('#movieReseedBtn').on('click', function() {
	if (!confirm('恢复内置源（豪华资源、爱坤资源）？已存在或已启用的会保留。')) { return; }
	$('#movieSourcesStatus').text('恢复中...');
	$.post('/movie/reseed', null, function(data) {
		if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { data = null; } }
		if (data && data.code === 'ok') {
			$('#movieSourcesStatus').text(data.msg || '已恢复');
			movieLoadSources();
		} else {
			$('#movieSourcesStatus').text(data && data.msg ? data.msg : '恢复失败');
		}
	}).fail(function() { $('#movieSourcesStatus').text('恢复请求失败'); });
});
// 添加自定义源（增强探测通过才保存）
$('#movieAddBtn').on('click', function() {
	var name = ($('#movieAddName').val() || '').trim();
	var api = ($('#movieAddApi').val() || '').trim();
	if (!name || !api) { alert('请填写名称与接口地址'); return; }
	$('#movieSourcesStatus').text('添加中（正在探测接口）...');
	$.post('/movie/addSource', { name: name, api: api }, function(data) {
		if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) { data = null; } }
		if (data && data.code === 'ok') {
			$('#movieSourcesStatus').text(data.msg || '已添加');
			$('#movieAddName').val('');
			$('#movieAddApi').val('');
			movieLoadSources();
		} else {
			var msg = data && data.msg ? data.msg : '添加失败';
			$('#movieSourcesStatus').text(msg);
			alert(msg);
		}
	}).fail(function() { $('#movieSourcesStatus').text('添加请求失败'); });
});
// 回到列表（窄屏堆叠模式显示）
$('#movieBackTop').on('click', function() {
	$('html, body').animate({ scrollTop: 0 }, 200);
});
// 切到影视仓 Tab 时加载源列表与播放状态
$(document).on('click', 'button.tab[data-tab="movie"]', function() {
	movieLoadSources();
	movieRefreshPlayState();
});
// 每 5 秒刷新播放状态
setInterval(movieRefreshPlayState, 5000);
// ===== 直播源更新（从影视仓 tvbox 配置）=====
function liveUpdateStatus(msg, isError){
	$('#liveSourceStatus').text(msg).toggleClass('error', !!isError);
}
function liveLoadSources(force){
	liveUpdateStatus(force ? '正在刷新配置…' : '正在获取直播源…', false);
	$.get('/live/sources' + (force ? '?refresh=1' : ''), function(data){
		if(!data || !data.list){ liveUpdateStatus('获取失败：响应异常', true); return; }
		if(data.refreshing && data.list.length === 0){
			liveUpdateStatus('正在获取配置，请稍候…', false);
			setTimeout(function(){ liveLoadSources(false); }, 3e3);
			return;
		}
		renderLiveSources(data.list);
		if(data.refreshing){
			liveUpdateStatus('已列出 ' + data.list.length + ' 个源（正在后台刷新…）', false);
		}else{
			liveUpdateStatus('共 ' + data.list.length + ' 个直播源', false);
		}
	}, 'json').fail(function(){ liveUpdateStatus('获取失败，请重试', true); });
}
function renderLiveSources(list){
	var html = [];
	if(!list || list.length === 0){
		html.push('<div class="live-source-empty">暂无可用直播源，点击「刷新」重试</div>');
	}else{
		for(var i=0;i<list.length;i++){
			var it = list[i];
			html.push('<div class="live-source-item' + (it.error ? ' disabled' : '') + '">');
			html.push('<div class="live-source-name">' + (it.name || '（不可用）') + '</div>');
			html.push('<div class="live-source-from">来源：' + (it.configName || '-') + (it.error ? ('｜' + it.error) : '') + '</div>');
			if(!it.error){
				html.push('<button type="button" class="btn-primary btn-small live-source-apply" data-url="' + it.url + '" data-name="' + (it.name || '') + '">应用</button>');
			}
			html.push('</div>');
		}
	}
	$('#liveSourceList').html(html.join(''));
	$('#liveBackupList').addClass('hidden').empty();
}
function liveApply(url, name){
	if(!url) return;
	if(!confirm('将用该直播源整体替换当前电视频道列表（原列表会自动备份），确定继续？')) return;
	liveUpdateStatus('正在下载并更新…', false);
	$.post('/live/apply', {url:url, name:name}, function(data){
		if(data && data.code === 'ok'){
			if(!$('.tv-editor').hasClass('hidden')){
				$('.tv-editor').addClass('hidden');
				$('.tv-items').removeClass('hidden');
			}
			alert('直播源已更新：' + data.channelCount + ' 个频道，' + data.sourceCount + ' 个源（格式 ' + data.format + '）。');
			liveUpdateStatus('已更新：' + data.channelCount + ' 频道 / ' + data.sourceCount + ' 源', false);
			loadTVList();
		}else{
			alert('更新失败：' + (data && data.msg ? data.msg : '未知错误'));
			liveUpdateStatus('更新失败', true);
		}
	}, 'json').fail(function(){ alert('更新失败：请求出错'); liveUpdateStatus('更新失败', true); });
}
function liveRestore(){
	$.get('/live/backups', function(data){
		if(!data || !data.list || data.list.length === 0){ alert('暂无备份可还原'); return; }
		var list = data.list;
		var html = [];
		for(var i=0;i<list.length;i++){
			var t = new Date(list[i].time);
			html.push('<div class="live-backup-item"><span>' + t.toLocaleString() + '（' + Math.round(list[i].size/1024) + 'KB）</span>');
			html.push('<button type="button" class="btn-secondary btn-small live-backup-restore" data-file="' + list[i].file + '">还原</button></div>');
		}
		$('#liveBackupList').html(html.join('')).removeClass('hidden');
		liveUpdateStatus('请选择要还原的备份', false);
	}, 'json').fail(function(){ alert('获取备份列表失败'); });
}
function liveBackupRestore(file){
	if(!file) return;
	if(!confirm('确定还原到该备份？（当前列表会再次自动备份）')) return;
	$.post('/live/restore', {file:file}, function(data){
		if(data && data.code === 'ok'){
			alert('已还原到备份。');
			$('#liveBackupList').addClass('hidden').empty();
			loadTVList();
		}else{
			alert('还原失败：' + (data && data.msg ? data.msg : '未知错误'));
		}
	}, 'json').fail(function(){ alert('还原失败：请求出错'); });
}
$('#btnLiveUpdate').on('click', function(){
	var $panel = $('#liveSourcePanel');
	if($panel.hasClass('hidden')){
		$panel.removeClass('hidden');
		liveLoadSources(false);
	}else{
		$panel.addClass('hidden');
	}
});
$('#btnLiveRefresh').on('click', function(){ liveLoadSources(true); });
$('#btnLiveRestore').on('click', function(){ liveRestore(); });
$('#btnLivePanelClose').on('click', function(){ $('#liveSourcePanel').addClass('hidden'); });
$(document).on('click', '.live-source-apply', function(){ liveApply($(this).attr('data-url'), $(this).attr('data-name')); });
$(document).on('click', '.live-backup-restore', function(){ liveBackupRestore($(this).attr('data-file')); });
