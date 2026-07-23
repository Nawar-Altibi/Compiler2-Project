# Expected: three FUNCTION_CALL_ERROR errors.
def simple_func(a, b):
    return a + b

simple_func(10)
simple_func(1, 2, 3)

def no_args():
    return True

no_args(True, False)

class Calculator:
    def add(self, x, y):
        return x + y

# Method-call arity is intentionally outside this semantic phase.
calc = Calculator()
calc.add(5, 5)
