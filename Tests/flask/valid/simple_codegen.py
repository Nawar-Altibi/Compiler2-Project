def f(a):
    return a + 1

def g(a):
    return f(a) * 2

def h(a):
    return g(a) + f(a)

print(h(5))