%% this is erlang

-module(accumulator).
-export([start/1, loop/2]).

start(Downstream) ->
  spawn(accumulator, loop, [0, Downstream]).

loop(Val, Downstream) ->
  Downstream ! {accumulation, Val},  
  receive
    {increment, Amount} ->
      loop(Val + Amount, Downstream)
  end.
  