"use strict";
const $ = id => document.getElementById(id);
const providers = [
  ["SMB","NAS / SMB","","Username","Password","Use a hostname reachable by your TV. Enter a share name and optional folder path."],
  ["JELLYFIN","Jellyfin","","Username","Password","Your Jellyfin server account."], ["EMBY","Emby","","Username","Password","Your Emby server account."],
  ["PLEX","Plex","","Username (optional)","Plex token","Use your Plex Media Server URL and X-Plex-Token with photo library access."],
  ["IMMICH","Immich","","Username (optional)","API key","API key permissions: album.read, asset.read and asset.download."],
  ...["NEXTCLOUD","OWNCLOUD","SYNOLOGY","SEAFILE","WEBDAV","PHOTOPRISM"].map(p=>[p,({NEXTCLOUD:"Nextcloud",OWNCLOUD:"ownCloud",SYNOLOGY:"Synology WebDAV",SEAFILE:"Seafile WebDAV",WEBDAV:"WebDAV",PHOTOPRISM:"PhotoPrism WebDAV"})[p],"","Username","App password","Enter the complete WebDAV URL of your photo folder. WebDAV must be enabled." ]),
  ["DROPBOX","Dropbox","https://api.dropboxapi.com/2/","Username (optional)","Access token","Read scopes: files.metadata.read and files.content.read. Renew expired tokens here."],
  ["ONEDRIVE","OneDrive","https://graph.microsoft.com/v1.0/","Username (optional)","Access token","Microsoft Graph token with Files.Read. Renew expired tokens here."],
  ["BOX","Box","https://api.box.com/2.0/","Username (optional)","Access token","A Box application access token with folder read access. Renew expired tokens here."],
  ["S3","S3 / MinIO","","Access key ID","Secret access key","Enter the bucket endpoint. Permanent keys only; custom endpoints use us-east-1."],
  ["FLICKR","Flickr public albums","https://www.flickr.com/services/rest/","User ID (NSID)","API key","Public Flickr albums only."]
];
let account = null;
let busy = false;
let credentialId = null;
function status(text) { $("status").textContent = text; }
async function request(path, method = "GET", body) {
  const response = await fetch(path, {method, credentials:"same-origin", headers:{"Content-Type":"application/json", ...(method !== "GET" ? {"X-CSRF-Token":account?.csrfToken || ""} : {})}, ...(body ? {body:JSON.stringify(body)} : {})});
  if (response.status === 401) { account = null; $("account").hidden = true; $("welcome").hidden = false; throw Error("Please sign in to continue."); }
  if (!response.ok) { const message = await response.json().catch(()=>({})); throw Error(message.error || "Could not complete the request. Try again."); }
  return response.status === 204 ? null : response.json();
}
async function act(work) {
  if(busy) return;
  const buttons=[...document.querySelectorAll("button")].map(b=>[b,b.disabled]);
  busy = true; buttons.forEach(([b])=>b.disabled=true); status("");
  try { await work(); } catch(error) { status(error.message); }
  finally { busy=false; buttons.forEach(([b,disabled])=>b.disabled=disabled); if(picker)pickerControls(!picker.listing); }
}
function item(title, subtitle, action, handler) {
  const row = document.createElement("div"); row.className="item";
  const content = document.createElement("div"), label=document.createElement("strong"), description=document.createElement("p"), button=document.createElement("button");
  label.textContent=title; description.textContent=subtitle; content.append(label,description); button.textContent=action; button.className="secondary"; button.onclick=()=>act(handler); row.append(content,button); return row;
}
async function load() {
  account = await request("/api/account/");
  $("welcome").hidden=true; $("account").hidden=false; $("greeting").textContent=account.user.name + "’s gallery";
  $("theme").value=account.settings.theme || "KELLI"; $("duration").value=(account.settings.slideDurationMillis || 15000)/1000;
  $("clock").checked=account.settings.clockOverlayEnabled || false; $("metadata").checked=account.settings.metadataOverlayEnabled || false;
  $("devices").replaceChildren(...account.devices.map(d=>item(d.name,"Linked screen","Revoke",async()=>{await request("/api/account/devices/"+encodeURIComponent(d.id),"DELETE");await load();status("Screen access revoked.");})));
  if(!account.devices.length) $("devices").textContent="No screens linked yet.";
  $("connections").replaceChildren(...account.connections.map(c=>{
    const row=item(c.name,c.provider.replaceAll("_"," ")+" · "+c.folderCount+" photo folders","Remove",async()=>{await request("/api/account/connections/"+encodeURIComponent(c.id)+"?revision="+account.revision,"DELETE");await load();status("Connection removed. Sync your TV to apply the change.");});
    const actions=document.createElement("div");actions.className="actions";actions.append(row.lastChild);
    if(!c.provider.startsWith("GOOGLE_")){
      const choose=document.createElement("button");choose.textContent="Choose photos";choose.onclick=()=>openPicker(c);actions.prepend(choose);
      const edit=document.createElement("button");edit.textContent="Update login";edit.className="secondary";edit.onclick=()=>{credentialId=c.id;$("credential-name").textContent=c.name;$("credential-secret").value="";$("credential-dialog").showModal();};actions.append(edit);
    }
    row.firstChild.lastChild.textContent=c.provider.replaceAll("_"," ")+" · "+(c.roots?.map(r=>r.name).join(", ") || "Choose photos to start your slideshow");
    row.append(actions);return row;
  }));
  if(!account.connections.length) $("connections").textContent="Your saved photo connections will appear here.";
}
async function pairing(code) {
  const normalized=code.replace(/[\s-]/g,"").toUpperCase();
  if(!/^[A-Z2-9]{8}$/.test(normalized)) throw Error("Enter the eight-character code shown on your TV.");
  const data = await request("/api/account/pairings/"+normalized);
  $("pair-panel").hidden=false; $("pair-name").textContent=data.name; $("pair-code").textContent=normalized.slice(0,4)+" "+normalized.slice(4);
  $("approve").onclick=()=>act(async()=>{await request("/api/account/pairings/approve","POST",{userCode:normalized});$("pair-panel").hidden=true;history.replaceState({},"","/");await load();status("TV approved. Continue on your TV.");});
  $("pair-panel").scrollIntoView({behavior:"smooth",block:"start"});
}
$("settings-form").onsubmit=e=>{e.preventDefault();act(async()=>{
  await request("/api/account/settings","PUT",{expectedRevision:account.revision,settings:{...account.settings,theme:$("theme").value,slideDurationMillis:Number($("duration").value)*1000,clockOverlayEnabled:$("clock").checked,metadataOverlayEnabled:$("metadata").checked}});
  await load(); status("Settings saved. Your linked TV will receive them on its next sync.");
});};
$("credential-cancel").onclick=()=>$("credential-dialog").close();
$("credential-dialog").addEventListener("close",()=>{$("credential-secret").value="";credentialId=null;});
$("credential-form").onsubmit=e=>{e.preventDefault();act(async()=>{
  await request("/api/account/connections/"+encodeURIComponent(credentialId)+"/credential","PUT",{expectedRevision:account.revision,secret:$("credential-secret").value});
  $("credential-dialog").close();await load();status("Login updated. Your selected photo folders are unchanged.");
});};
$("logout").onclick=()=>act(async()=>{await request("/api/account/logout","POST");location.assign("/");});
$("code-form").onsubmit=e=>{e.preventDefault();act(()=>pairing($("code").value));};
for(const p of providers){const option=document.createElement("option");option.value=p[0];option.textContent=p[1];$("provider").append(option);}
function providerChanged(){const p=providers.find(p=>p[0]===$("provider").value);$("nas-group").hidden=p[0]!=="SMB";$("endpoint-group").hidden=p[0]==="SMB"||!!p[2];$("endpoint").value=p[2];$("endpoint").required=p[0]!=="SMB";$("host").required=$("share").required=p[0]==="SMB";$("username-label").textContent=p[3];$("secret-label").textContent=p[4];$("provider-help").textContent=p[5];$("secret").value="";}
$("provider").onchange=providerChanged;providerChanged();
$("connection-form").onsubmit=e=>{e.preventDefault();act(async()=>{
  const provider=$("provider").value, name=$("name").value.trim(), configuration={endpoint:$("endpoint").value.trim(),username:$("username").value.trim(),secret:$("secret").value};
  let objectId=provider==="BOX" ? "0" : "root";
  if(provider==="SMB"){configuration.host=$("host").value.trim();configuration.port=445;configuration.share=$("share").value.trim();configuration.domain="";objectId=$("path").value.trim() || ".";}
  else if(["NEXTCLOUD","OWNCLOUD","SYNOLOGY","SEAFILE","WEBDAV","PHOTOPRISM"].includes(provider)){objectId=new URL(configuration.endpoint.replace(/\/$/,"")+"/").pathname;}
  const id="cloud-"+crypto.randomUUID();
  await request("/api/account/connections","PUT",{expectedRevision:account.revision,connection:{id,provider,name,configuration,roots:[]}});
  $("secret").value="";$("connection-form").reset();providerChanged();$("connection-details").open=false;await load();status("Connection saved. Choose the albums or folders for your slideshow.");
  openPicker(account.connections.find(c=>c.id===id),provider==="SMB"&&objectId!=="." ? {objectId,name:objectId}:null);
});};
let picker=null, browseGeneration=0;
function pickerControls(loading=false){
  $("browse-refresh").disabled=loading||!$("browse-device").value;
  $("browse-back").disabled=loading||!picker?.trail.length;
  $("browse-more").disabled=loading;
  $("browse-select").disabled=loading||!picker?.listing||picker.connection.provider==="IMMICH"&&picker.listing.objectId==="root";
}
function renderSelected(){
  $("browse-selected").replaceChildren(...picker.roots.map(root=>item(root.name,root.includeDescendants?"Includes subfolders":"This folder only","Remove",async()=>{picker.roots=picker.roots.filter(r=>r.objectId!==root.objectId);renderSelected();})));
  if(!picker.roots.length)$("browse-selected").textContent="No photos selected. Add an album or folder above.";
}
function openPicker(connection,start=null){
  browseGeneration++;
  picker={connection,roots:structuredClone(connection.roots||[]),revision:account.revision,trail:[],current:start,listing:null};
  $("photo-title").textContent=connection.name;
  $("browse-device").replaceChildren(...account.devices.map(device=>{const option=document.createElement("option");option.value=device.id;option.textContent=device.name+(device.lastSeenAt&&Date.now()-Date.parse(device.lastSeenAt)<45000?" · Online":" · Open Kanvas on this TV");return option;}));
  $("browse-folders").replaceChildren();$("browse-location").textContent=start?.name||"Albums and folders";$("browse-status").textContent=account.devices.length?"Ready to browse through your TV.":"Link your TV using Account setup first, then return here to choose photos.";
  $("browse-save-status").textContent="";$("browse-more").hidden=true;$("browse-descendants").checked=true;$("browse-exclusive").checked=account.settings.accountPhotosOnly!==false;
  renderSelected();pickerControls();$("photo-dialog").showModal();
  // Start after the save action has re-enabled ordinary page controls.
  if(account.devices.length)setTimeout(()=>browse(start),0);
}
async function browse(target=null,cursor=null){
  if(!picker||!$("browse-device").value)return;
  const generation=++browseGeneration;
  picker.listing=null;pickerControls(true);$("browse-status").textContent="Waiting for your TV… Keep Kanvas open on the screen. Requires version 1.0.22 or later.";
  if(!cursor)$("browse-folders").replaceChildren();
  try{
    const job=await request("/api/account/browse","POST",{deviceId:$("browse-device").value,connectionId:picker.connection.id,objectId:target?.objectId||null,cursor});
    while(generation===browseGeneration&&$("photo-dialog").open){
      await new Promise(resolve=>setTimeout(resolve,1500));
      if(generation!==browseGeneration)return;
      const result=await request("/api/account/browse/"+job.id);
      if(result.status==="expired")throw Error("The TV did not respond. Open Kanvas on your linked TV and try Browse again.");
      if(result.status!=="complete")continue;
      if(result.listing.error)throw Error(({authentication:"Login was rejected. Close this picker and use Update login to check your API key or password.",network:"The TV could not reach this service. Check its Wi-Fi and server address.",unavailable:"The TV could not open this folder. Check the connection and try again."})[result.listing.error]||"This service could not be browsed.");
      picker.listing=result.listing;picker.current={objectId:result.listing.objectId,name:target?.name||picker.connection.name};
      $("browse-location").textContent=picker.current.name;
      for(const folder of result.listing.folders){const button=document.createElement("button");button.className="folder";button.textContent=folder.name;button.onclick=()=>{picker.trail.push(picker.current);browse(folder);};$("browse-folders").append(button);}
      $("browse-status").textContent=result.listing.folders.length?"Open an album or folder, then add it to your slideshow.":result.listing.photoCount?"Photos found. Add this album or folder to your slideshow.":"No albums, folders or photos on this page.";
      $("browse-more").hidden=!result.listing.nextCursor;pickerControls();return;
    }
  }catch(error){if(generation===browseGeneration){$("browse-status").textContent=error.message;pickerControls();}}
}
$("browse-refresh").onclick=()=>browse(picker.current);
$("browse-back").onclick=()=>browse(picker.trail.pop()||null);
$("browse-more").onclick=()=>browse(picker.current,picker.listing.nextCursor);
$("browse-device").onchange=()=>{picker.trail=[];browse();};
$("browse-select").onclick=()=>{
  if(!picker.listing)return;
  const root={objectId:picker.current.objectId,name:picker.current.name,includeDescendants:$("browse-descendants").checked,includedFilterIds:[]};
  picker.roots=picker.roots.filter(r=>r.objectId!==root.objectId);picker.roots.push(root);renderSelected();$("browse-save-status").textContent="Added. Save slideshow to apply your selection.";
};
$("browse-save").onclick=async()=>{
  $("browse-save").disabled=true;
  try{await request("/api/account/connections/"+encodeURIComponent(picker.connection.id)+"/roots","PUT",{expectedRevision:picker.revision,roots:picker.roots,accountPhotosOnly:$("browse-exclusive").checked});$("photo-dialog").close();await load();status("Slideshow saved. Your TV receives the selection within 30 seconds while Kanvas is open.");}
  catch(error){$("browse-save-status").textContent=error.message;}
  finally{$("browse-save").disabled=false;}
};
$("photo-close").onclick=$("browse-cancel").onclick=()=>$("photo-dialog").close();
$("photo-dialog").addEventListener("close",()=>{browseGeneration++;picker=null;});
(async()=>{
  const params=new URLSearchParams(location.search), code=params.get("code");
  if(location.pathname==="/pair"&&code&&/^[A-Z2-9]{8}$/.test(code))$("signin").href="/api/auth/login?returnTo="+encodeURIComponent("/pair?code="+code);
  try{await load();if(code)await pairing(code);}catch(error){if(account)status(error.message);}
  if(params.get("error"))status("Sign-in could not be completed. Please try again or contact the server administrator.");
})();
