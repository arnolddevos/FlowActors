%% this is Erlang

-module(scaling).
-export([start/1, init/1]).

start(Downstream) ->
  spawn(accumulator, init, [Downstream]).

init(Downstream) ->
  receive
    {scale_factor, Factor} ->
      loop(Downstream, Factor)
  end.

loop(Downstream, Factor) ->
  receive
    {measurement, Value} ->
      Downstream ! {scaled_measurement, Value * Factor},
      loop(Downstream, Factor);

    {scale_factor, NewFactor} ->
      loop(Downstream, NewFactor);

    _ ->
      loop(Downstream, Factor)
  end.
