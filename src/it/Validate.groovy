class Validate {
  private File basedir

  Validate(File basedir) {
    this.basedir = basedir
  }

  def log() {
    def log = { f ->
      [
        [~/(?ms)^Running post-build script:.*/, ''],
        [~/(?m)^\[INFO\] Download(?:ed|ing) from .*\n/, ''],
        [~/(?m)^(\[INFO\] Total time: +)(\d+\.\d+ s)$/, '$1‹time›'],
        [~/(?m)^(\[INFO\] Finished at: +)(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+\d\d:\d\d)$/,
         '$1‹date›'],
      ].inject(new File(basedir, f).getText("UTF-8")) { s, p -> s.replaceAll(p[0], p[1]) }
    }
    assert log('build.log') == log('expected.log')
    this
  }
}
