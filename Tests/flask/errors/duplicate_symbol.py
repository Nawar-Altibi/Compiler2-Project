# Expected: two DUPLICATE_SYMBOL errors from Pass 1.
def foo():
    return 1

def foo():
    return 2

class Bar:
    pass

class Bar:
    pass
