var userSession = {}

blockstack.newUserSession=function(domainName) {
   const appConfig = new blockstack.AppConfig(domainName);
   userSession = new blockstack.UserSession({ appConfig:appConfig, sessionStore:androidSessionStore });
   console.log("before fetch: " + fetch)
   var p = fetch("https://google.com")
   console.log("after fetch: " + p)
   p.then(function(result){
     console.log("google + " + result);
   })

   return JSON.stringify(userSession);
}

blockstack.isUserSignedIn = function() {
  return userSession.isUserSignedIn()
}

blockstack.signIn = function(domainName, appPrivateKey, identityAddress, hubUrl, userDataString) {
  const appConfig = new blockstack.AppConfig(domainName);
  console.log("userData : " + userDataString)
  const userData = JSON.parse(userDataString);
  const sessionOptions = { appPrivateKey, identityAddress, hubUrl, userData };
  userSession = new blockstack.UserSession({ appConfig, sessionOptions })
  console.log("signedIn: " + userSession.isUserSignedIn())
}

blockstack.signUserOut = function() {
  userSession.signUserOut()
}

blockstack.getFile = function(path,options, uniqueIdentifier) {
    return userSession.getFile(path, JSON.parse(options));
}

blockstack.putFile = function(path, contentString, options, uniqueIdentifier, binary) {
    return userSession.putFile(path, contentString, JSON.parse(options));
}


blockstack.encryptContent = function(contentString, options) {
    return userSession.encryptContent(contentString, JSON.parse(options))
}

blockstack.decryptContent = function(cipherTextString, options, binary) {
    return userSession.decryptContent(cipherTextString, JSON.parse(options))
}

var fetchPromises = {}
fetch = function(url, options){
  console.log("fetch 2:" + blockstack.Bluebird)
  console.log("fetch 2:" + blockstack.Promise)
  var promise = new blockstack.Bluebird(function(resolve, reject) {
    console.log('fetch ' + url)
    fetchPromises.resolve = resolve
    android.fetch(url, JSON.stringify(options))
  })
  return promise
}

blockstack.fetchResolve = function(url, response) {
  console.log('resolved ' + url)
  fetchPromises.resolve(JSON.parse(reponse))
}


