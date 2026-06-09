# Symbol Table Error: DUPLICATE_SYMBOL
# Expects: Function/class already defined in this scope

def foo():
    return 1

def foo():
    return 2

class Bar:
    pass

class Bar:
    pass
