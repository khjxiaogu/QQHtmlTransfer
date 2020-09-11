# QQHtmlTransfer
To show mirai message on html page.
for mirai 0.5.2
# Usage
0. Run with mirai.
1. Go to `HtmlMessageTransfer/config.yml`
3. Add allowed group and public password as you wish, and configurate ssl if necessary.
4. Restart mirai. 
5. Open index.html or index_ssl.html, you would see
```
var qqgroupid=0;
var confpassword="123456";//the same as in configuration
```
change to your group id and public password.
6. Open the website, you would see message from qq group show in page instantly.
# Warning
It's for test use only, should not be use in production fields. There's no safety promise for this. 
