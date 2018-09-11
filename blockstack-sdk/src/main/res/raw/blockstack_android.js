var userSession = {};

blockstack.newUserSession=function(domainName) {
   const appConfig = new blockstack.AppConfig(domainName);
   userSession = new blockstack.UserSession({ appConfig });
   return JSON.stringify(userSession);
}