package constraints.normequality

import constraints.GenBase

class Gen extends GenBase[UVar] {
  type V = UVar
  private var _nextId = 0
  def freshUVar(): UVar = {
    val v = UVar(Symbol("x$" + _nextId))
    _nextId += 1
    v
  }
}
