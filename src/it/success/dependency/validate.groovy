new GroovyScriptEngine(basedir.parentFile.parent)
  .loadScriptByName('Validate.groovy')
  .newInstance(basedir)
  .log()
true
